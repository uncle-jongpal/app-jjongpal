package app.jongpal.jjongpal.capture.call

import app.jongpal.jjongpal.capture.call.CallNumberMatcher.CallLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 통화 녹음 ↔ 상대 번호 짝짓기 회귀 테스트.
 * 예전엔 녹음 끝 시각을 통화 시작 시각과 비교해 10분 넘는 통화는 번호를 못 찾았다.
 */
class CallNumberMatcherTest {

    // ── 파일 이름에서 번호 뽑기 ──

    @Test
    fun samsungCallFile_digits() {
        assertEquals("01020718733", CallNumberMatcher.parseNumberFromFileName("통화 01020718733_260820_142707.m4a"))
    }

    @Test
    fun englishCallPrefix_withDashes() {
        assertEquals("01012345678", CallNumberMatcher.parseNumberFromFileName("Call 010-1234-5678_261012_184232.m4a"))
    }

    @Test
    fun internationalPlusKept() {
        assertEquals("+821012345678", CallNumberMatcher.parseNumberFromFileName("통화 +821012345678_261012_184232.m4a"))
    }

    @Test
    fun contactNameFile_null() {
        assertNull(CallNumberMatcher.parseNumberFromFileName("통화 홍길동_261012_184232.m4a"))
    }

    @Test
    fun voiceMemo_null() {
        assertNull(CallNumberMatcher.parseNumberFromFileName("음성 261012_141820.m4a"))
    }

    @Test
    fun shortServiceNumber_null() {
        // 너무 짧은 숫자열은 번호로 안 본다
        assertNull(CallNumberMatcher.parseNumberFromFileName("통화 114_261012_184232.m4a"))
    }

    @Test
    fun unrelatedName_null() {
        assertNull(CallNumberMatcher.parseNumberFromFileName("recording_01012345678.m4a"))
    }

    // ── 통화기록 짝짓기 (끝 시각 기준) ──

    private val base = 1_760_000_000_000L

    @Test
    fun longCall_matchesByEndTime() {
        // 40분 통화 — 시작 시각으로는 40분 차이지만 끝 시각은 녹음 수정 시각과 몇 초 차이
        val entries = listOf(CallLogEntry("010-1111-2222", base, 40 * 60L))
        val recEnd = base + 40 * 60_000L + 5_000L
        assertEquals("01011112222", CallNumberMatcher.bestMatch(entries, recEnd))
    }

    @Test
    fun picksClosestEnd() {
        val entries = listOf(
            CallLogEntry("01000000001", base, 60L),                 // 끝: base+1분
            CallLogEntry("01000000002", base + 2 * 60_000L, 120L),   // 끝: base+4분
        )
        assertEquals("01000000002", CallNumberMatcher.bestMatch(entries, base + 4 * 60_000L + 2_000L))
    }

    @Test
    fun ringingTimeWithinTolerance() {
        // DATE 에 벨 울린 시간이 포함돼 DATE+DURATION 이 실제 끝보다 40초 이르다
        val entries = listOf(CallLogEntry("01033334444", base, 300L))
        assertEquals("01033334444", CallNumberMatcher.bestMatch(entries, base + 300_000L + 40_000L))
    }

    @Test
    fun outsideTolerance_null() {
        val entries = listOf(CallLogEntry("01033334444", base, 60L))
        assertNull(CallNumberMatcher.bestMatch(entries, base + 60_000L + CallNumberMatcher.END_TOLERANCE_MS + 1))
    }

    @Test
    fun missedCallsIgnored() {
        // 부재중(연결 0초)은 녹음이 없으므로 짝 후보 아님
        val entries = listOf(
            CallLogEntry("01099999999", base + 60_000L, 0L),
            CallLogEntry("01011112222", base, 50L),
        )
        assertEquals("01011112222", CallNumberMatcher.bestMatch(entries, base + 60_000L))
    }

    @Test
    fun blankNumberIgnored() {
        val entries = listOf(CallLogEntry("", base, 60L), CallLogEntry(null, base, 60L))
        assertNull(CallNumberMatcher.bestMatch(entries, base + 60_000L))
    }

    @Test
    fun emptyEntries_null() {
        assertNull(CallNumberMatcher.bestMatch(emptyList(), base))
    }

    // ── 음성 메모 판별 (번호만 안 붙임, 캡처는 그대로) ──

    @Test
    fun voiceMemoDetection() {
        assertTrue(CallNumberMatcher.looksLikeVoiceMemo("/storage/emulated/0/Recordings/Voice Recorder/음성 261012_141820.m4a"))
        assertTrue(CallNumberMatcher.looksLikeVoiceMemo("/storage/emulated/0/Recordings/음성 261012_141820.m4a"))
        assertFalse(CallNumberMatcher.looksLikeVoiceMemo("/storage/emulated/0/Recordings/Call/통화 홍길동_261012_184232.m4a"))
    }
}
