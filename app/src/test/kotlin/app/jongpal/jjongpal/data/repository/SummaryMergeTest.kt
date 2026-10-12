package app.jongpal.jjongpal.data.repository

import app.jongpal.jjongpal.data.local.SummaryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 서버 요약으로 덮어쓸 때 로컬 전용 값(원문·재생 경로·읽음)이 지워지지 않는지 */
class SummaryMergeTest {

    private fun server(pinned: Boolean = false) = SummaryEntity(
        id = "s1", eventId = "e1", userId = 1, transcriptText = null,
        summary = "새 요약", rawJson = "{}", createdAt = 10L, pinned = pinned,
    )

    private val local = SummaryEntity(
        id = "s1", eventId = "e1", userId = 1, transcriptText = "옛 원문",
        segmentsJson = "[{\"s\":0}]", audioPath = "/a.m4a",
        summary = "옛 요약", rawJson = "{}", createdAt = 10L, viewed = true, pinned = false,
    )

    @Test
    fun keepsLocalOnlyFields_whenNoNewTranscript() {
        val m = mergeWithLocal(server(), local, transcript = null, audioPath = null)
        assertEquals("새 요약", m.summary)
        assertEquals("옛 원문", m.transcriptText)
        assertEquals("[{\"s\":0}]", m.segmentsJson)
        assertEquals("/a.m4a", m.audioPath)
        assertTrue(m.viewed)
    }

    @Test
    fun newTranscriptWins() {
        val m = mergeWithLocal(server(), local, transcript = "새 원문" to "[]", audioPath = "/b.m4a")
        assertEquals("새 원문", m.transcriptText)
        assertEquals("[]", m.segmentsJson)
        assertEquals("/b.m4a", m.audioPath)
    }

    @Test
    fun newRow_noLocal() {
        val m = mergeWithLocal(server(pinned = true), null, transcript = null, audioPath = null)
        assertNull(m.transcriptText)
        assertFalse(m.viewed)
        assertTrue(m.pinned)
    }
}
