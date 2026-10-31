package app.jongpal.jjongpal.capture.call

import app.jongpal.jjongpal.data.local.EventDao
import app.jongpal.jjongpal.data.repository.EventRepository
import app.jongpal.jjongpal.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** 밀린 녹음을 어디까지 올릴지 — 확인 창의 선택지 */
enum class BacklogRange(val label: String, val days: Int) {
    DAYS7("최근 7일", 7),
    DAYS30("최근 30일", 30),
    ALL("전체", 0),
    NONE("올리지 않기 (지금부터 통화만)", -1),
}

/** 아직 서버에 안 올라간 통화 녹음 수(기간별, 누적) */
data class BacklogCounts(val days7: Int, val days30: Int, val all: Int)

/**
 * 밀린 통화 녹음 확인(2026-10-31).
 *
 * 로그인 직후·오래 쉬었다 켠 뒤 옛 녹음을 통째로 올리던 문제(9월 녹음 100여 개가 한꺼번에 올라감) →
 * 감시기(CallRecordingObserver)가 보류 시각 이전 파일을 멈춰 두고, 여기서 개수를 세어 사용자에게 범위를 묻는다.
 * 고른 범위 밖 대기 통화는 HELD(올리지 않음, 폰 원본은 그대로), 안쪽은 전체 재스캔으로 빠짐없이 등록.
 */
@Singleton
class BacklogManager @Inject constructor(
    private val observer: CallRecordingObserver,
    private val eventDao: EventDao,
    private val eventRepository: EventRepository,
    private val syncScheduler: SyncScheduler,
) {
    /** 0 이 아니면 확인 창을 띄워야 함 */
    val hold: StateFlow<Long> = observer.backlogHold

    suspend fun counts(): BacklogCounts = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val holdMs = observer.holdMs().takeIf { it > 0L } ?: now
        val synced = eventDao.callSyncStates().filter { it.syncStatus == "SYNCED" }.map { it.id }.toHashSet()
        val files = observer.listBacklogFiles(holdMs, observer.firstInstallMs())
            .filter { (path, _) -> CallId.deterministic(path) !in synced }
        BacklogCounts(
            days7 = files.count { it.second >= now - DAY_MS * 7 },
            days30 = files.count { it.second >= now - DAY_MS * 30 },
            all = files.size,
        )
    }

    suspend fun choose(range: BacklogRange) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val first = observer.firstInstallMs()
        val floor = when (range) {
            BacklogRange.ALL -> first
            BacklogRange.NONE -> observer.holdMs().takeIf { it > 0L } ?: now
            else -> maxOf(now - DAY_MS * range.days, first)
        }
        val held = eventDao.holdCallsBefore(floor)
        val released = eventDao.releaseHeldSince(floor)
        // 7일보다 오래된 통화를 직접 고른 경우만 서버에 "처리해도 됨" 표시(3일 동안 — 밀린 업로드가 끝날 시간)
        val allowOldUntil = if (floor < now - DAY_MS * 7) now + DAY_MS * 3 else 0L
        observer.applyBacklogFloor(floor, allowOldUntil)
        Timber.i("밀린 녹음 범위=%s floor=%d HELD=%d 되살림=%d", range, floor, held, released)

        // 감시 서비스가 안 떠 있어도 바로 반영되게 직접 콜백을 넘겨 전체 재스캔
        observer.scanNow("backlog-choice") { path, ts ->
            runBlocking {
                eventRepository.captureCallFile(
                    eventId = CallId.deterministic(path),
                    filePath = path,
                    durationSec = null,
                    timestampMs = ts,
                )
            }
        }
        syncScheduler.scheduleNow()
    }

    /** 설정 > "지난 녹음 올리기" — 확인 창을 다시 띄움 */
    fun reopen() = observer.holdBacklog("settings")

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
