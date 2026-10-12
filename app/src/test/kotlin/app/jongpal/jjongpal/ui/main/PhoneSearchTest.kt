package app.jongpal.jjongpal.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 번호 검색어 → 통화 event id 목록 */
class PhoneSearchTest {
    private val phones = mapOf(
        "e1" to "01020718733",
        "e2" to "+82-10-1234-5678",
        "e3" to "0212345678",
    )

    @Test
    fun matchesDigitsInside() {
        assertEquals(listOf("e1"), phoneSearchEventIds("0718", phones))
    }

    @Test
    fun ignoresDashesInQueryAndPhone() {
        assertTrue(phoneSearchEventIds("1234-5678", phones).contains("e2"))
    }

    @Test
    fun tooShort_empty() {
        assertTrue(phoneSearchEventIds("0", phones).isEmpty())
        assertTrue(phoneSearchEventIds("홍길동", phones).isEmpty())
    }

    @Test
    fun capped() {
        val many = (1..2000).associate { "e$it" to "0101234${it.toString().padStart(4, '0')}" }
        assertEquals(900, phoneSearchEventIds("010", many).size)
    }
}
