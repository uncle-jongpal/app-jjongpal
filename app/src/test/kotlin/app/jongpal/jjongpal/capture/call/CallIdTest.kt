package app.jongpal.jjongpal.capture.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 수집 누락 방지 생명선인 결정적 통화 ID 회귀 테스트.
 * 이 ID 알고리즘이 바뀌면 기존 폰의 dedup 이 어긋나 같은 통화가 중복 등록된다.
 */
class CallIdTest {

    @Test
    fun sameInput_sameId() {
        val p = "/storage/emulated/0/Recordings/Call/20260812_홍길동_010.m4a"
        assertEquals(CallId.deterministic(p), CallId.deterministic(p))
    }

    @Test
    fun format_isCallPrefixPlus32Hex() {
        val id = CallId.deterministic("/x/y.m4a")
        assertTrue("call- 접두 필요: $id", id.startsWith("call-"))
        val hex = id.removePrefix("call-")
        assertEquals("32 헥스여야 함", 32, hex.length)
        assertTrue("소문자 헥스만: $hex", hex.all { it in "0123456789abcdef" })
    }

    @Test
    fun differentPaths_differentIds() {
        assertNotEquals(
            CallId.deterministic("/rec/a.m4a"),
            CallId.deterministic("/rec/b.m4a"),
        )
    }

    @Test
    fun caseSensitivePath_changesId() {
        // 경로 대소문자/한 글자 차이도 다른 ID (충돌 없음)
        assertNotEquals(
            CallId.deterministic("/rec/call.m4a"),
            CallId.deterministic("/rec/Call.m4a"),
        )
    }

    @Test
    fun knownVector_isStable() {
        // ⚠️ 이 값이 바뀌면 기존 폰 dedup 과 어긋남 — 알고리즘 변경 감지용 고정 벡터.
        // SHA-256("call:/test/call.m4a") 의 앞 16바이트 hex.
        assertEquals(
            "call-0f3a0257a7f4b6fc294a2f44be67ecb5",
            CallId.deterministic("/test/call.m4a"),
        )
    }
}
