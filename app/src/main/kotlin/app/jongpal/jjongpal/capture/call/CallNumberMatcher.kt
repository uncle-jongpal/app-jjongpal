package app.jongpal.jjongpal.capture.call

/**
 * 통화 녹음 파일 ↔ 상대 전화번호 짝짓기 (안드로이드 의존 없는 순수 로직 — 단위 테스트로 고정).
 *
 * 예전엔 녹음 파일 수정 시각(= 통화 **끝**)을 통화기록 DATE(= 통화 **시작**)와 바로 비교하고
 * 10분 넘게 차이나면 버렸다 → 10분 넘는 통화는 번호를 영영 못 찾았다(2026-10 기준 493건 중 236건 번호 없음).
 * 이제 통화기록의 끝 시각(DATE + DURATION) 과 비교한다.
 */
object CallNumberMatcher {

    /** 통화기록 한 줄 — 시작 시각(밀리초), 연결 시간(초), 상대 번호 */
    data class CallLogEntry(val number: String?, val dateMs: Long, val durationSec: Long)

    /**
     * 녹음 끝 시각과 통화 끝 시각 허용 오차.
     * DATE 는 발신/수신 시작(벨 울리는 시간 포함), DURATION 은 연결된 시간만이라
     * DATE+DURATION 이 실제 끝보다 벨 울린 만큼 이르다 → 넉넉히 3분.
     */
    const val END_TOLERANCE_MS = 3 * 60_000L

    // 삼성 통화 녹음 파일 이름: "통화 01012345678_YYMMDD_HHMMSS.m4a" / "Call 010-1234-5678_..."
    // 연락처에 저장된 상대면 번호 대신 이름이 들어가므로(예: "통화 홍길동_...") 그땐 못 뽑는다.
    private val samsungNameRegex = Regex("""^(?:통화|Call)\s+(\+?[0-9][0-9 \-]{5,})_\d{6}_\d{6}\.[A-Za-z0-9]+$""")

    /**
     * 파일 이름에서 숫자 전화번호를 뽑는다. 없으면 null.
     * 음성 메모("음성 261012_141820.m4a")·연락처 이름 파일은 null — 캡처는 그대로 되고 번호만 비는 것.
     */
    fun parseNumberFromFileName(fileName: String): String? {
        val m = samsungNameRegex.matchEntire(fileName.trim()) ?: return null
        val raw = m.groupValues[1]
        val digits = normalize(raw) ?: return null
        // 숫자 6자리 미만은 번호로 보기 어려움(우연한 숫자열 방지)
        return if (digits.count { it.isDigit() } >= 6) digits else null
    }

    /**
     * 녹음 끝 시각([recordingEndMs], 파일 수정 시각)에 끝난 통화의 번호를 고른다.
     * - 연결 안 된 통화(DURATION 0 = 부재중·거절)는 녹음이 없으므로 제외
     * - 통화 끝(DATE + DURATION) 과의 차이가 가장 작은 것, 단 [END_TOLERANCE_MS] 이내만
     */
    fun bestMatch(entries: List<CallLogEntry>, recordingEndMs: Long, toleranceMs: Long = END_TOLERANCE_MS): String? {
        var best: String? = null
        var bestDiff = Long.MAX_VALUE
        for (e in entries) {
            if (e.durationSec <= 0L) continue
            val num = normalize(e.number) ?: continue
            val endMs = e.dateMs + e.durationSec * 1000L
            val diff = kotlin.math.abs(endMs - recordingEndMs)
            if (diff < bestDiff) {
                bestDiff = diff
                best = num
            }
        }
        return if (bestDiff <= toleranceMs) best else null
    }

    /**
     * 음성 메모로 보이는 파일인가 — 이런 파일엔 통화기록 번호를 붙이지 않는다
     * (통화 직후 녹음한 메모에 직전 통화 번호가 잘못 붙는 것 방지). 캡처·업로드 대상에서 빼는 건 아님.
     */
    fun looksLikeVoiceMemo(filePath: String): Boolean {
        val norm = filePath.replace('\\', '/')
        val name = norm.substringAfterLast('/')
        return norm.contains("/Voice Recorder/", ignoreCase = true) ||
            name.startsWith("음성 ") || name.startsWith("Voice ", ignoreCase = true)
    }

    /** 숫자와 맨 앞 '+' 만 남김. 비면 null */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim()
        val plus = t.startsWith("+")
        val digits = t.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return if (plus) "+$digits" else digits
    }
}
