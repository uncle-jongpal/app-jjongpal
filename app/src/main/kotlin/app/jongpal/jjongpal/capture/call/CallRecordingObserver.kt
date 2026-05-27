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
    suspend fun scanNow(reason: String, cbOverride: ((String, Long) -> Unit)? = null) {
        val cb = cbOverride ?: callback ?: return
        scanMutex.withLock {
            scanMediaStore(reason, cb)
            scanFallbackDirs(cb, reason)
        }
    }

    private suspend fun scanMediaStore(onScanReason: String, cb: (String, Long) -> Unit) {
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

        // RELATIVE_PATH 는 API 29 (안드로이드 10) 부터 사용 가능
        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val selection: String
        val args: Array<String>
        if (hasRelativePath) {
            val pathClauses = relativePathPrefixes.joinToString(" OR ") { "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" }
            selection = "($pathClauses) AND ${MediaStore.Audio.Media._ID} > ?"
            args = (relativePathPrefixes.map { "$it%" } + lastSeenId.toString()).toTypedArray()
        } else {
            // 9 이하: DATA 컬럼으로 검색
            val dataClauses = fallbackDirs.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" }
            selection = "($dataClauses) AND ${MediaStore.Audio.Media._ID} > ?"
            args = (fallbackDirs.map { "$it/%" } + lastSeenId.toString()).toTypedArray()
        }

        var maxId = lastSeenId
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
                        // 아직 쓰는 중 — 다음 스캔에서 잡힘. _ID 진행은 안 함.
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
                    var consumed = false
                    try {
                        cb(data, effectiveTsMs)
                        count++
                        consumed = true
                    } catch (e: Exception) {
                        Timber.e(e, "callback error for %s", data)
                    }
                    // 인서트 실패 시 식별자 안 박음 — 다음 스캔에서 재시도 가능
                    if (consumed && id > maxId) maxId = id
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "MediaStore query failed")
            return
        }
        if (maxId > lastSeenId) {
            prefs.edit().putLong(KEY_LAST_MEDIASTORE_ID, maxId).apply()
        }
        Timber.i("scanMediaStore reason=%s count=%d lastSeenId=%d", onScanReason, count, maxId)
    }

    private fun scanFallbackDirs(cb: (String, Long) -> Unit, reason: String) {
        val lastSeenMs = prefs.getLong(KEY_LAST_FILESCAN_MS, 0L)
        val installTsMs = prefs.getLong(KEY_INSTALL_TS_MS, 0L)
        val threshold = maxOf(lastSeenMs, installTsMs)
        var maxMs = lastSeenMs
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
                var consumed = false
                try {
                    cb(file.absolutePath, mtime)
                    count++
                    consumed = true
                } catch (e: Exception) {
                    Timber.e(e, "callback error for %s", file.absolutePath)
                }
                if (consumed && mtime > maxMs) maxMs = mtime
            }
        }
        if (maxMs > lastSeenMs) {
            prefs.edit().putLong(KEY_LAST_FILESCAN_MS, maxMs).apply()
        }
        Timber.i("scanFallbackDirs reason=%s count=%d lastSeenMs=%d", reason, count, maxMs)
    }

    private fun isAudioExt(nameOrPath: String): Boolean {
        val lower = nameOrPath.lowercase()
        return lower.endsWith(".m4a") || lower.endsWith(".mp3") ||
                lower.endsWith(".amr") || lower.endsWith(".wav") ||
                lower.endsWith(".aac") || lower.endsWith(".3gp")
    }

    companion object {
        private const val PREFS = "call_observer_state"
        private const val KEY_LAST_MEDIASTORE_ID = "last_mediastore_id"
        private const val KEY_LAST_FILESCAN_MS = "last_filescan_ms"
        private const val KEY_INSTALL_TS_MS = "install_ts_ms"
    }
}
