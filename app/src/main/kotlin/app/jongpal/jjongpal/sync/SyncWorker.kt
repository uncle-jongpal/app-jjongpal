package app.jongpal.jjongpal.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.data.repository.AppointmentRepository
import app.jongpal.jjongpal.data.repository.EventRepository
import app.jongpal.jjongpal.data.repository.SummaryRepository
import app.jongpal.jjongpal.data.repository.TodoRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val eventRepository: EventRepository,
    private val todoRepository: TodoRepository,
    private val summaryRepository: SummaryRepository,
    private val appointmentRepository: AppointmentRepository,
    private val tokenManager: TokenManager,
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

            // 2) 할 일 양방향
            val pushed = todoRepository.pushToServer()
            val pulled = todoRepository.pullFromServer()
            Timber.i("todos pushed=%d pulled=%d", pushed, pulled)

            // 3) 요약 / 약속 가져오기
            val sums = summaryRepository.pullFromServer()
            val appts = appointmentRepository.pullFromServer()
            Timber.i("summaries pulled=%d appointments pulled=%d", sums, appts)

            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "SyncWorker error")
            Result.retry()
        }
    }
}
