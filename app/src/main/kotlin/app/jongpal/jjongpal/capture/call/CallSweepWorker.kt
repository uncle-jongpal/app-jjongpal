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

        // 자체 콜백 박아서 옵저버 (CallRecordingObserver) 의 콜백 미설정 상태에도 안전하게 동작
        var foundCount = 0
        try {
            observer.scanNow("sweep-worker") { filePath, ts ->
                val id = deterministicId(filePath)
                // suspend 컨텍스트 안에서 호출됐지만 cb 는 동기 람다 — runBlocking 으로 직접 호출
                kotlinx.coroutines.runBlocking {
                    eventRepository.captureCallFile(
                        eventId = id,
                        filePath = filePath,
                        durationSec = null,
                        timestampMs = ts,
                    )
                }
                foundCount++
            }
            if (foundCount > 0) {
                syncScheduler.scheduleNow()
                Timber.i("sweep found %d new calls", foundCount)
            }
        } catch (e: Exception) {
            Timber.e(e, "sweep worker error")
            return Result.retry()
        }
        return Result.success()
    }

    // 결정적 ID 로직은 CallId 로 통합(FileObserverService 와 동일 보장, 회귀 테스트로 고정)
    private fun deterministicId(filePath: String): String = CallId.deterministic(filePath)

    companion object {
        private const val UNIQUE_PERIODIC = "call-sweep-periodic"
        private const val UNIQUE_ONCE = "call-sweep-once"

        fun schedulePeriodic(context: Context) {
            // 네트워크 조건 안 박음 — 폴더 스캔은 오프라인에서도 동작해야 함.
            // 발견된 통화 음원의 업로드는 SyncScheduler / 업로드 작업자 (CallUploadWorker) 자체 네트워크 조건이 처리
            val req = PeriodicWorkRequestBuilder<CallSweepWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }

        /** 통화 종료 등 특정 시점 이후 지연 실행 — 녹음 파일 완성 시차 대응 */
        fun runAfter(context: Context, delaySec: Long, tag: String) {
            val req = OneTimeWorkRequestBuilder<CallSweepWorker>()
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$UNIQUE_ONCE-$tag",
                ExistingWorkPolicy.REPLACE,
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
