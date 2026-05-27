package app.jongpal.jjongpal.capture.call

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.data.repository.EventRepository
import app.jongpal.jjongpal.sync.SyncScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 통화 녹음 누락 방지 안전망.
 *
 * 미디어 인덱스 (MediaStore) 의 변경 감지기가 일부 환경에서 콜백을 놓치는 경우 대비,
 * 주기적 (15분) 으로 + 부팅 시 + 앱 시작 시 한 번씩 폴더 스캔.
 *
 * 새로 발견된 파일은 EventRepository.captureCallFile 로 데이터베이스 등록 +
 * SyncScheduler.scheduleNow 로 즉시 업로드 작업 등록.
 *
 * 같은 파일 재스캔 시 결정적 식별자 (filePath 기반 SHA-256 16바이트 헥스) +
 * EventDao.insert 의 IGNORE 충돌 처리로 자동 중복 방지.
 */
@HiltWorker
class CallSweepWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val observer: CallRecordingObserver,
    private val eventRepository: EventRepository,
    private val tokenManager: TokenManager,
    private val syncScheduler: SyncScheduler,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!tokenManager.hasValidSession()) {
            Timber.d("sweep skipped — no session")
            return Result.success()
        }

        // 스캔 콜백 미설정 시 (관측 서비스 미가동) 잠시 콜백 직접 박아서 스캔만 실행
        // start() 가 이미 호출됐다면 그 콜백 그대로 사용됨
        try {
            observer.scanNow(reason = "sweep-worker")
            syncScheduler.scheduleNow()
        } catch (e: Exception) {
            Timber.e(e, "sweep worker error")
            return Result.retry()
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_PERIODIC = "call-sweep-periodic"
        private const val UNIQUE_ONCE = "call-sweep-once"

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<CallSweepWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }

        fun runOnce(context: Context) {
            val req = OneTimeWorkRequestBuilder<CallSweepWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONCE,
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }
    }
}
