package app.jongpal.jjongpal.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.data.repository.EventRepository
import app.jongpal.jjongpal.data.repository.SummaryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val eventRepository: EventRepository,
    private val summaryRepository: SummaryRepository,
    private val tokenManager: TokenManager,
    private val syncStatusStore: SyncStatusStore,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!tokenManager.hasValidSession()) {
            Timber.d("SyncWorker — no session, skip")
            return Result.success()
        }
        val userId = tokenManager.userId
        if (userId <= 0) {
            Timber.w("SyncWorker — invalid user_id (%d), skip", userId)
            return Result.success()
        }
        val deviceId = tokenManager.deviceId

        return try {
            // 1) 폰의 쌓인 이벤트 (알림) PC 로 푸시
            val sent = eventRepository.syncPending(userId, deviceId)
            Timber.i("events synced ok=%d fail=%d", sent.ok, sent.failed)

            // 2) 할 일·약속 동기화는 뺐다 — v2 화면에서 감춰져 있는데 15분마다 2000건씩 주고받던 낭비.
            //    (표·코드는 보존 — 다시 쓰게 되면 여기서 todoRepository / appointmentRepository 호출만 되살리면 됨)

            // 3) 요약 가져오기 — 정기는 최근 것만, 사용자가 당겨서 새로고침하면 전체
            val full = inputData.getBoolean(KEY_FULL_PULL, false)
            val limit = if (full) SummaryRepository.FULL_LIMIT else SummaryRepository.PERIODIC_LIMIT
            val sums = summaryRepository.pullFromServer(limit)
            Timber.i("summaries pulled=%s (full=%s)", sums, full)

            // "PC 연결" 표시등 = 요약 받기가 실제로 서버 2xx 를 받았는가. (예전엔 무조건 성공으로 기록)
            syncStatusStore.record(ok = sums != null)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "SyncWorker error")
            syncStatusStore.record(ok = false)
            Result.retry()
        }
    }

    companion object {
        /** 입력값: true 면 요약 전체(FULL_LIMIT)를 받는다 — 당겨서 새로고침 전용 */
        const val KEY_FULL_PULL = "full_pull"
    }
}
