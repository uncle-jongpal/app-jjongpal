package app.jongpal.jjongpal.data.repository

import app.jongpal.jjongpal.data.remote.FailedItemDto
import app.jongpal.jjongpal.data.remote.PcApi
import app.jongpal.jjongpal.data.remote.RetryReq
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// 서버에서 처리 막힌 항목 조회 + 수동 재시도.
// 실패 목록은 휘발성이라 로컬 디비에 안 쌓고, 필요할 때만 서버에서 직접 가져옴.
@Singleton
class RetryRepository @Inject constructor(
    private val pcApi: PcApi,
) {
    suspend fun listFailed(): List<FailedItemDto> = try {
        val resp = pcApi.listFailed()
        if (resp.isSuccessful) resp.body() ?: emptyList()
        else { Timber.w("listFailed ${resp.code()}"); emptyList() }
    } catch (e: Exception) {
        Timber.e(e, "listFailed error")
        emptyList()
    }

    // 되돌린 행(reset_count 합)이 1 이상일 때만 실제 재시도로 간주.
    // RPC 는 되돌릴 게 없어도 200 + 빈 배열을 주므로, 200 만으로 성공 처리하면 안 됨.
    suspend fun retry(eventId: String): Boolean = try {
        val resp = pcApi.retryFailedItem(RetryReq(p_event_id = eventId))
        if (!resp.isSuccessful) {
            Timber.w("retry ${resp.code()} for $eventId")
            return false
        }
        val resetTotal = (resp.body() ?: emptyList()).sumOf { it.reset_count }
        if (resetTotal == 0) Timber.w("retry no-op (0 rows reset) for $eventId")
        resetTotal > 0
    } catch (e: Exception) {
        Timber.e(e, "retry error for $eventId")
        false
    }
}
