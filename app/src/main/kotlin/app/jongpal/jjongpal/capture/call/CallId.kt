package app.jongpal.jjongpal.capture.call

import java.security.MessageDigest

/**
 * 통화 녹음 파일의 결정적 ID 생성 (수집 누락 방지 생명선).
 *
 * 같은 파일 경로 → 항상 같은 ID. Room 의 INSERT IGNORE 와 결합해
 * 같은 통화가 여러 경로(옵서버/스윕/부팅 스윕)로 중복 감지돼도 한 번만 등록되게 한다.
 *
 * FileObserverService / CallSweepWorker 양쪽에서 동일 로직을 써야 하므로 여기로 통합.
 * ⚠️ 이 알고리즘을 바꾸면 기존 폰에 이미 등록된 통화들과 ID 가 어긋나
 *    같은 파일이 새 ID 로 재등록(중복)될 수 있다. CallIdTest 의 회귀 벡터로 고정.
 */
object CallId {
    fun deterministic(filePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("call:" + filePath).toByteArray(Charsets.UTF_8))
        // SHA-256 앞 16바이트 = 32 헥스 (충돌 무시 가능 수준)
        val hex = StringBuilder(32)
        for (i in 0 until 16) {
            val b = digest[i].toInt() and 0xFF
            if (b < 0x10) hex.append('0')
            hex.append(b.toString(16))
        }
        return "call-" + hex.toString()
    }
}
