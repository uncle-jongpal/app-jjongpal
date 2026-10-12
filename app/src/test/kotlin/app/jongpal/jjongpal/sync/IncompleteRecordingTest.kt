package app.jongpal.jjongpal.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 끝내 완성 안 된(moov 없는) 녹음을 언제 실패로 돌리는지 */
class IncompleteRecordingTest {
    private val now = 1_760_000_000_000L

    @Test
    fun recentFile_keepsWaiting() {
        assertFalse(isIncompleteGivenUp(now, now - 10 * 60_000L))
    }

    @Test
    fun exactlyOneHour_keepsWaiting() {
        assertFalse(isIncompleteGivenUp(now, now - INCOMPLETE_GIVE_UP_MS))
    }

    @Test
    fun olderThanOneHour_givesUp() {
        assertTrue(isIncompleteGivenUp(now, now - INCOMPLETE_GIVE_UP_MS - 1))
    }

    @Test
    fun unknownModifiedTime_keepsWaiting() {
        // lastModified() 가 0 = 파일 정보 못 읽음 → 깨졌다고 단정하지 않음
        assertFalse(isIncompleteGivenUp(now, 0L))
    }
}
