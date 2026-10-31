package app.jongpal.jjongpal.capture.call

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 통화 녹음 파일 감시기.
 *
 * 안드로이드 10+ 의 스코프드 스토리지 정책상 외부 앱이 만든 파일은 FileObserver 로 못 감지함
 * (특히 /storage/emulated/0/Recordings/Call/ 처럼 다른 앱이 소유한 폴더).
 * 대신 MediaStore.Audio.Media 의 ContentObserver 를 등록 + 새 항목을 질의하는 방식으로 동작.
 *
 * 콜백: 새 통화 파일 감지 시 onNewCallFile(filePath, timestampMs) 호출.
 * 호출 측 (FileObserverService) 이 EventRepository.captureCallFile + SyncScheduler 호출.
 */
@Singleton
class CallRecordingObserver @Inject constructor(@ApplicationContext private val context: Context) {

    // 통화 녹음 폴더 후보 — 제조사별 (MediaStore RELATIVE_PATH 매칭용 + 직접 파일 시스템 스캔용)
    private val relativePathPrefixes: List<String> = listOf(
        "Recordings/Call/",
        "Recordings/",
        "Call/",
        "CallRecordings/",
        "MIUI/sound_recorder/call_rec/",
        "Sounds/CallRecord/",
        "Music/Recordings/",
    )

    // 직접 파일 시스템 스캔용 (안드로이드 9 이하 또는 MediaStore 가 누락한 파일 대비)
    private val fallbackDirs: List<String> by lazy {
        val base = Environment.getExternalStorageDirectory().absolutePath
        relativePathPrefixes.map { "$base/${it.trimEnd('/')}" }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    private var contentObserver: ContentObserver? = null
    private var callback: ((String, Long) -> Unit)? = null

    // ── 밀린 녹음 확인(2026-10-31) ──
    // 로그인 직후·오래 쉬었다 켠 뒤엔 옛 녹음을 바로 올리지 않고 "보류 시각" 이전 파일을 멈춰 둔다.
    // 사용자가 범위(최근 7일 등)를 고르면 BacklogManager 가 바닥 시각을 정하고 전체 재스캔한다.
    private val _backlogHold = MutableStateFlow(0L)
    /** 0 이 아니면 그 시각 이전 녹음은 사용자가 고를 때까지 올리지 않음 */
    val backlogHold: StateFlow<Long> = _backlogHold.asStateFlow()

    fun holdBacklog(reason: String) {
        if (prefs.getLong(KEY_HOLD_BEFORE_MS, 0L) > 0L) return
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_HOLD_BEFORE_MS, now).apply()
        _backlogHold.value = now
        Timber.i("밀린 녹음 보류 시작 (%s)", reason)
    }

    /** 지금 보류 중인 시각(0 = 보류 없음) — 업로드 작업이 이 시각 이전 통화는 미뤄 둔다 */
    fun holdMs(): Long = prefs.getLong(KEY_HOLD_BEFORE_MS, 0L)

    /** 처음 설치한 시각 — "전체" 범위의 바닥. 바닥 시각을 옮겨도 이 값은 그대로. */
    fun firstInstallMs(): Long = prefs.getLong(KEY_FIRST_INSTALL_TS_MS, 0L).takeIf { it > 0L }
        ?: prefs.getLong(KEY_INSTALL_TS_MS, 0L)

    /** 사용자가 고른 범위 적용: 이 시각 이전 녹음은 앞으로 무시, 보류 해제, 다음 스캔은 책갈피 무시하고 전체. */
    fun applyBacklogFloor(floorMs: Long, allowOldUntilMs: Long) {
        prefs.edit()
            .putLong(KEY_FIRST_INSTALL_TS_MS, firstInstallMs())
            .putLong(KEY_INSTALL_TS_MS, floorMs)
            .putLong(KEY_HOLD_BEFORE_MS, 0L)
            .putBoolean(KEY_FULL_SCAN_PENDING, true)
            .putLong(KEY_ALLOW_OLD_UNTIL_MS, allowOldUntilMs)
            .apply()
        _backlogHold.value = 0L
    }

    /** 사용자가 7일보다 넓은 범위를 직접 골랐으면 잠시 동안 서버에 "오래된 것도 처리" 표시를 보낸다. */
    fun allowOldUploadNow(): Boolean = System.currentTimeMillis() < prefs.getLong(KEY_ALLOW_OLD_UNTIL_MS, 0L)

    /** 바닥 시각부터 보류 시각 전까지, 통화 녹음 폴더에 있는 파일 (경로, 시각) — 확인 창의 개수 세기용 */
    fun listBacklogFiles(holdMs: Long, sinceMs: Long): List<Pair<String, Long>> {
        val out = HashMap<String, Long>()
        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.SIZE,
            )
            val (selection, args) = if (hasRelativePath) {
                relativePathPrefixes.joinToString(" OR ") { "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" } to
                    relativePathPrefixes.map { "$it%" }.toTypedArray()
            } else {
                fallbackDirs.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" } to
                    fallbackDirs.map { "$it/%" }.toTypedArray()
            }
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val data = c.getString(0) ?: continue
                    if (!isAudioExt(data) || c.getLong(3) <= 0L) continue
                    val sec = c.getLong(2).takeIf { it > 0L } ?: c.getLong(1)
                    val ts = if (sec > 0L) sec * 1000L else File(data).lastModified()
                    if (ts in sinceMs until holdMs) out[data] = ts
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "backlog MediaStore query failed")
        }
        fallbackDirs.forEach { path ->
            val files = try { File(path).listFiles() } catch (_: SecurityException) { null } ?: return@forEach
            for (f in files) {
                if (!f.isFile || !isAudioExt(f.name) || f.length() <= 0L) continue
                val ts = f.lastModified()
                if (ts in sinceMs until holdMs) out.putIfAbsent(f.absolutePath, ts)
            }
        }
        return out.map { it.key to it.value }
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun start(onNewCallFile: (filePath: String, timestampMs: Long) -> Unit) {
        stop()
        callback = onNewCallFile

        // 첫 시작 시각 박기 (이전 통화 녹음 일괄 처리 방지 — 이 시각 이전 파일은 무시)
        if (prefs.getLong(KEY_INSTALL_TS_MS, 0L) == 0L) {
            val nowMs = System.currentTimeMillis()
            // 미디어 인덱스 의 현재 최대 식별자 도 같이 박아서 옛 항목 일괄 수집 방지
            var currentMaxId = -1L
            try {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    null, null,
                    "${MediaStore.Audio.Media._ID} DESC LIMIT 1",
                )?.use { cur ->
                    if (cur.moveToFirst()) currentMaxId = cur.getLong(0)
                }
            } catch (e: Exception) {
                Timber.w(e, "seed lastSeenId failed")
            }
            prefs.edit()
                .putLong(KEY_INSTALL_TS_MS, nowMs)
                .putLong(KEY_LAST_MEDIASTORE_ID, currentMaxId)
                .putLong(KEY_LAST_FILESCAN_MS, nowMs)
                .apply()
            Timber.i("seeded: installTs=%d lastMediaId=%d", nowMs, currentMaxId)
        }

        _backlogHold.value = prefs.getLong(KEY_HOLD_BEFORE_MS, 0L)
        val nowMs = System.currentTimeMillis()
        val lastActive = prefs.getLong(KEY_LAST_ACTIVE_MS, 0L)
        if (!prefs.getBoolean(KEY_BACKLOG_ASKED_V1, false)) {
            // 0.5.12 첫 실행 — 이미 쌓인 녹음이 있으면 한 번 물어본다(없으면 화면이 조용히 넘어감)
            prefs.edit().putBoolean(KEY_BACKLOG_ASKED_V1, true).apply()
            holdBacklog("first-run-v0.5.12")
        } else if (lastActive > 0L && nowMs - lastActive > BACKLOG_GAP_MS) {
            holdBacklog("idle ${(nowMs - lastActive) / 3_600_000}h")
        }

        // MediaStore 의 오디오 콘텐츠 URI 변경 감지 등록 — 시스템이 미디어 인덱스 갱신할 때마다 호출됨
        val resolver: ContentResolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                scope.launch {
                    val cb = callback ?: return@launch
                    scanMutex.withLock { scanMediaStore("mediastore-change", cb) }
                }
            }
        }
        try {
            resolver.registerContentObserver(uri, true, observer)
            contentObserver = observer
            Timber.i("ContentObserver registered on MediaStore.Audio")
        } catch (e: Exception) {
            Timber.e(e, "register ContentObserver failed")
        }

        // 시작 직후 초기 스캔 (그동안 누락된 파일 일괄 처리)
        scope.launch { scanNow(reason = "start") }

        // 일회성 백필 — 옛 책갈피 전진 버그로 묻힌 과거 녹음 복구.
        // 책갈피(_ID/시각) 를 무시하고 설치 이후 모든 녹음을 재스캔. 이미 올린 건 로컬 IGNORE 로 자동 중복 방지.
        if (!prefs.getBoolean(KEY_BACKFILL_V1_DONE, false)) {
            scope.launch {
                scanNow(reason = "backfill-v1", ignoreWatermark = true)
                prefs.edit().putBoolean(KEY_BACKFILL_V1_DONE, true).apply()
            }
        }
    }

    fun stop() {
        contentObserver?.let {
            try { context.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        contentObserver = null
        callback = null
        // 진행 중 코루틴 모두 취소 — 스코프는 객체 평생 그대로 (싱글톤이라 새 start 시 재사용)
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
    }

    /**
     * 외부에서 명시적으로 스캔 트리거 (앱 시작 시 / 주기 작업 / 부팅 시).
     * - 콜백 (callback) 인자 박으면 그걸 사용 (관측 서비스 미가동 시 작업자 (CallSweepWorker) 가 직접 호출용)
     * - 인자 없으면 start() 로 박힌 콜백 사용
     */
    suspend fun scanNow(
        reason: String,
        ignoreWatermark: Boolean = false,
        cbOverride: ((String, Long) -> Unit)? = null,
    ) {
        val cb = cbOverride ?: callback ?: return
        // 사용자가 범위를 고른 직후엔 책갈피 무시하고 바닥 시각 이후 전부 다시 훑는다(로그아웃 중 놓친 것 포함)
        val full = ignoreWatermark || prefs.getBoolean(KEY_FULL_SCAN_PENDING, false)
        scanMutex.withLock {
            scanMediaStore(reason, cb, full)
            scanFallbackDirs(cb, reason, full)
        }
        if (full && !ignoreWatermark) prefs.edit().putBoolean(KEY_FULL_SCAN_PENDING, false).apply()
        prefs.edit().putLong(KEY_LAST_ACTIVE_MS, System.currentTimeMillis()).apply()
        maybeRescanRecent(cb)
    }

    /**
     * 상시 재확인 — 하루 1회, 최근 7일치를 책갈피 무시하고 다시 훑는다.
     *
     * 책갈피(_ID) 방식은 "나중에 완성된 긴 녹음"을 영구히 놓칠 수 있다.
     * (녹음 중 다른 통화가 먼저 올라가면 책갈피가 앞질러 감 — 2026-07-30 1시간 28분 녹음 누락 사례)
     * 이미 올린 건 로컬 중복 방지로 무시되므로 재확인은 안전하다.
     */
    private suspend fun maybeRescanRecent(cb: (String, Long) -> Unit) {
        val last = prefs.getLong(KEY_LAST_RESCAN_MS, 0L)
        val now = System.currentTimeMillis()
        if (now - last < RESCAN_INTERVAL_MS) return
        scanMutex.withLock {
            Timber.i("rescan-recent 시작 (최근 7일 재확인)")
            scanMediaStore("rescan-recent", cb, ignoreWatermark = true, sinceMs = now - RESCAN_WINDOW_MS)
            scanFallbackDirs(cb, "rescan-recent", ignoreWatermark = true, sinceMs = now - RESCAN_WINDOW_MS)
        }
        prefs.edit().putLong(KEY_LAST_RESCAN_MS, now).apply()
    }

    private suspend fun scanMediaStore(
        onScanReason: String,
        cb: (String, Long) -> Unit,
        ignoreWatermark: Boolean = false,
        sinceMs: Long = 0L,
    ) {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
        )

        // 마지막 본 식별자 이후만 — 재시작 후에도 이어서 동작
        val lastSeenId = prefs.getLong(KEY_LAST_MEDIASTORE_ID, -1L)
        // 백필 모드에선 책갈피 무시하고 설치 이후 전체 재질의 (묻힌 녹음 복구). 저장 책갈피는 뒤로 안 밀림.
        val queryFloorId = if (ignoreWatermark) -1L else lastSeenId

        // RELATIVE_PATH 는 API 29 (안드로이드 10) 부터 사용 가능
        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val selection: String
        val args: Array<String>
        if (hasRelativePath) {
            val pathClauses = relativePathPrefixes.joinToString(" OR ") { "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" }
            selection = "($pathClauses) AND ${MediaStore.Audio.Media._ID} > ?"
            args = (relativePathPrefixes.map { "$it%" } + queryFloorId.toString()).toTypedArray()
        } else {
            // 9 이하: DATA 컬럼으로 검색
            val dataClauses = fallbackDirs.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" }
            selection = "($dataClauses) AND ${MediaStore.Audio.Media._ID} > ?"
            args = (fallbackDirs.map { "$it/%" } + queryFloorId.toString()).toTypedArray()
        }

        var maxId = lastSeenId
        // 이번 스캔에서 본but안 올린(아직 쓰는 중 / 콜백 실패) 가장 낮은 식별자.
        // 책갈피가 이 항목을 넘어 전진하면 영영 묻히므로, 전진 상한으로 사용.
        var pendingMinId = Long.MAX_VALUE
        var count = 0
        try {
            resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, args, "${MediaStore.Audio.Media._ID} ASC")?.use { cursor ->
                val idxId = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val idxData = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val idxDateAdded = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val idxDateModified = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val idxSize = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = if (idxId >= 0) cursor.getLong(idxId) else -1L
                    val data = if (idxData >= 0) cursor.getString(idxData) else null
                    val dateAddedSec = if (idxDateAdded >= 0) cursor.getLong(idxDateAdded) else 0L
                    val dateModifiedSec = if (idxDateModified >= 0) cursor.getLong(idxDateModified) else 0L
                    val size = if (idxSize >= 0) cursor.getLong(idxSize) else 0L

                    if (data.isNullOrBlank() || !isAudioExt(data)) {
                        if (id > maxId) maxId = id
                        continue
                    }
                    if (size <= 0L) {
                        // 아직 쓰는 중 — 다음 스캔에서 잡힘. 책갈피가 이 항목을 넘지 못하게 보류 최저값 갱신.
                        if (id < pendingMinId) pendingMinId = id
                        continue
                    }
                    val tsMs = (if (dateModifiedSec > 0L) dateModifiedSec else dateAddedSec) * 1000L
                    val effectiveTsMs = if (tsMs > 0L) tsMs else File(data).lastModified()
                    val installTsMs = prefs.getLong(KEY_INSTALL_TS_MS, 0L)
                    // 앱 설치 (또는 이 옵저버 첫 시작) 이전 파일은 무시 — 옛 녹음 일괄 처리 방지
                    if (installTsMs > 0L && effectiveTsMs < installTsMs) {
                        if (id > maxId) maxId = id
                        continue
                    }
                    // 밀린 녹음 보류 중 — 보류 시각 이전 파일은 사용자가 범위를 고를 때까지 손대지 않음
                    val holdMs = prefs.getLong(KEY_HOLD_BEFORE_MS, 0L)
                    if (holdMs > 0L && effectiveTsMs < holdMs) {
                        if (id > maxId) maxId = id
                        continue
                    }
                    // 재확인 모드 — 지정 시각 이전 파일은 건너뜀 (최근 것만 다시 본다)
                    if (sinceMs > 0L && effectiveTsMs < sinceMs) {
                        if (id > maxId) maxId = id
                        continue
                    }
                    // 아직 쓰는 중인 파일 걸러내기 — 파일 크기가 안정된 뒤에만 등록.
                    // (녹음 중인 파일을 미리 올려 잘린 내용이 저장되는 것 방지)
                    if (!isSizeStable(data, size)) {
                        if (id < pendingMinId) pendingMinId = id
                        continue
                    }
                    var consumed = false
                    try {
                        cb(data, effectiveTsMs)
                        count++
                        consumed = true
                    } catch (e: Exception) {
                        Timber.e(e, "callback error for %s", data)
                    }
                    if (consumed) {
                        if (id > maxId) maxId = id
                    } else {
                        // 콜백 실패 — 다음 스캔에서 재시도. 책갈피가 이 항목을 넘지 못하게 보류 최저값 갱신.
                        if (id < pendingMinId) pendingMinId = id
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "MediaStore query failed")
            return
        }
        // 책갈피는 보류 항목(아직 쓰는 중 / 실패) 직전까지만 전진 — 건너뛴 낮은 식별자가 묻히는 것 방지.
        val safeMax = if (pendingMinId != Long.MAX_VALUE) minOf(maxId, pendingMinId - 1) else maxId
        if (safeMax > lastSeenId) {
            prefs.edit().putLong(KEY_LAST_MEDIASTORE_ID, safeMax).apply()
        }
        Timber.i("scanMediaStore reason=%s count=%d lastSeenId=%d", onScanReason, count, safeMax)
    }

    private fun scanFallbackDirs(cb: (String, Long) -> Unit, reason: String, ignoreWatermark: Boolean = false, sinceMs: Long = 0L) {
        val lastSeenMs = prefs.getLong(KEY_LAST_FILESCAN_MS, 0L)
        val installTsMs = prefs.getLong(KEY_INSTALL_TS_MS, 0L)
        // 백필 모드에선 시각 책갈피 무시하고 설치 이후 전체 — 묻힌 녹음 복구. 저장 책갈피는 뒤로 안 밀림.
        val threshold = if (ignoreWatermark) maxOf(installTsMs, sinceMs) else maxOf(lastSeenMs, installTsMs)
        var maxMs = lastSeenMs
        // 콜백 실패한 가장 이른 시각 — 책갈피가 이를 넘으면 묻히므로 전진 상한.
        var pendingMinMs = Long.MAX_VALUE
        var count = 0
        fallbackDirs.forEach { path ->
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) return@forEach
            val files = try { dir.listFiles() } catch (_: SecurityException) { null } ?: return@forEach
            for (file in files) {
                if (!file.isFile) continue
                if (!isAudioExt(file.name)) continue
                if (file.length() <= 0L) continue
                val mtime = file.lastModified()
                if (mtime <= threshold) continue
                val holdMs = prefs.getLong(KEY_HOLD_BEFORE_MS, 0L)
                if (holdMs > 0L && mtime < holdMs) continue
                var consumed = false
                try {
                    cb(file.absolutePath, mtime)
                    count++
                    consumed = true
                } catch (e: Exception) {
                    Timber.e(e, "callback error for %s", file.absolutePath)
                }
                if (consumed) {
                    if (mtime > maxMs) maxMs = mtime
                } else {
                    if (mtime < pendingMinMs) pendingMinMs = mtime
                }
            }
        }
        // 책갈피는 실패 항목 직전까지만 전진.
        val safeMaxMs = if (pendingMinMs != Long.MAX_VALUE) minOf(maxMs, pendingMinMs - 1) else maxMs
        if (safeMaxMs > lastSeenMs) {
            prefs.edit().putLong(KEY_LAST_FILESCAN_MS, safeMaxMs).apply()
        }
        Timber.i("scanFallbackDirs reason=%s count=%d lastSeenMs=%d", reason, count, safeMaxMs)
    }

    private fun isAudioExt(nameOrPath: String): Boolean {
        val lower = nameOrPath.lowercase()
        return lower.endsWith(".m4a") || lower.endsWith(".mp3") ||
                lower.endsWith(".amr") || lower.endsWith(".wav") ||
                lower.endsWith(".aac") || lower.endsWith(".3gp")
    }

    /** 직전 스캔에서 본 파일 크기 — 두 번 연속 같아야 "다 쓰인 것"으로 본다 */
    private val lastSeenSizes = HashMap<String, Long>()

    private fun isSizeStable(path: String, size: Long): Boolean {
        if (size <= 0L) return false
        // 마지막 수정이 2분 넘게 지난 파일은 다 쓰인 것 — 밀린 녹음 재스캔이 한 번에 등록되게(두 번 볼 필요 없음)
        if (System.currentTimeMillis() - File(path).lastModified() > SETTLED_MS) {
            lastSeenSizes.remove(path)
            return true
        }
        val prev = lastSeenSizes[path]
        lastSeenSizes[path] = size
        // 처음 본 파일이면 다음 스캔까지 보류, 크기가 그대로면 완성으로 판단
        if (prev == null) return false
        if (prev != size) return false
        lastSeenSizes.remove(path)
        return true
    }

    companion object {
        private const val PREFS = "call_observer_state"
        private const val KEY_LAST_MEDIASTORE_ID = "last_mediastore_id"
        private const val KEY_LAST_FILESCAN_MS = "last_filescan_ms"
        private const val KEY_INSTALL_TS_MS = "install_ts_ms"
        private const val KEY_BACKFILL_V1_DONE = "backfill_v1_done"
        private const val KEY_LAST_RESCAN_MS = "last_rescan_ms"
        private const val KEY_HOLD_BEFORE_MS = "backlog_hold_before_ms"
        private const val KEY_FIRST_INSTALL_TS_MS = "first_install_ts_ms"
        private const val KEY_FULL_SCAN_PENDING = "full_scan_pending"
        private const val KEY_ALLOW_OLD_UNTIL_MS = "allow_old_upload_until_ms"
        private const val KEY_LAST_ACTIVE_MS = "last_active_ms"
        private const val KEY_BACKLOG_ASKED_V1 = "backlog_asked_v1"
        /** 이만큼 스캔이 없었으면(앱이 꺼져 있었거나 로그아웃) 다시 켤 때 밀린 녹음을 물어본다 */
        private const val SETTLED_MS = 2L * 60 * 1000
        private const val BACKLOG_GAP_MS = 2L * 24 * 60 * 60 * 1000
        /** 상시 재확인 주기 — 하루 1회 */
        private const val RESCAN_INTERVAL_MS = 24L * 60 * 60 * 1000
        /** 재확인 범위 — 최근 7일치 녹음은 책갈피와 무관하게 다시 훑는다 */
        const val RESCAN_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    }
}
