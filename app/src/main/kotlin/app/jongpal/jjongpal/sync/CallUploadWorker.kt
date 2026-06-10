package app.jongpal.jjongpal.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.data.local.EventDao
import app.jongpal.jjongpal.data.remote.UploadApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@HiltWorker
class CallUploadWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val eventDao: EventDao,
    private val uploadApi: UploadApi,
    private val tokenManager: TokenManager,
    private val moshi: Moshi,
) : CoroutineWorker(ctx, params) {

    private val mapAdapter = moshi.adapter<Map<String, Any?>>(MAP_TYPE)

    // SimpleDateFormat 은 스레드 안전 X — 인스턴스 필드로 두고 같은 인스턴스 안에서만 사용
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    companion object {
        private val MAP_TYPE = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        // 마지막 수정 후 이 시간(밀리초) 지나야 업로드 — 녹음 저장 마무리 대기.
        private const val SETTLE_MS = 20_000L
    }

    override suspend fun doWork(): Result {
        if (!tokenManager.hasValidSession()) return Result.success()

        // 과거 "업로드 중 파일이 자라 크기 불일치" 로 재시도 소진된 통화 재등록 (파일 안정됐으니 재업로드)
        val requeued = eventDao.requeueSizeMismatchCalls()
        if (requeued > 0) Timber.i("requeued %d size-mismatch calls", requeued)

        val pending = eventDao.pendingForSync()
            .filter { it.type == "call" }
        if (pending.isEmpty()) return Result.success()

        var ok = 0
        var failed = 0
        var deferred = 0
        val deviceId = tokenManager.deviceId

        for (event in pending) {
            val meta = event.metadataJson?.let { mapAdapter.fromJson(it) } ?: emptyMap()
            val filePath = meta["file_path"] as? String
            val duration = (meta["duration_sec"] as? Number)?.toInt()

            if (filePath.isNullOrBlank()) {
                eventDao.updateSync(event.id, "FAILED", null, "no file_path", 1)
                failed++
                continue
            }
            val file = File(filePath)
            if (!file.exists()) {
                eventDao.updateSync(event.id, "FAILED", null, "file missing", 1)
                failed++
                continue
            }
            // 녹음 저장 마무리 전이면 미루기 — 업로드 중 파일이 커져 크기 불일치로 실패하던 버그 방지.
            // PENDING 유지 + 재시도 횟수 안 올림 (실패로 안 침).
            if (System.currentTimeMillis() - file.lastModified() < SETTLE_MS) {
                deferred++
                continue
            }

            // 안정된 사본을 떠서 업로드 — 업로드 도중 원본이 바뀌어도 선언 크기와 전송 크기가 어긋나지 않음.
            val snapshot = File(applicationContext.cacheDir, "callup_${event.id}.m4a")
            try {
                file.copyTo(snapshot, overwrite = true)
                val body = snapshot.asRequestBody("audio/m4a".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", file.name, body)

                val resp = uploadApi.uploadAudio(
                    file = part,
                    eventId = event.id.toRequestBody("text/plain".toMediaTypeOrNull()),
                    deviceTimestamp = isoFormat.format(Date(event.timestamp)).toRequestBody("text/plain".toMediaTypeOrNull()),
                    deviceId = deviceId?.toRequestBody("text/plain".toMediaTypeOrNull()),
                    durationSec = duration?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull()),
                )
                if (resp.isSuccessful) {
                    eventDao.updateSync(event.id, "SYNCED", System.currentTimeMillis(), null, 0)
                    // 업로드 성공 시 폰 측 파일도 삭제 (디스크 누적 방지)
                    try { file.delete() } catch (_: Exception) {}
                    ok++
                } else {
                    eventDao.updateSync(event.id, "FAILED", null, "upload ${resp.code()}", 1)
                    failed++
                }
            } catch (e: Exception) {
                Timber.e(e, "upload error for %s", filePath)
                eventDao.updateSync(event.id, "FAILED", null, e.message ?: "error", 1)
                failed++
            } finally {
                try { snapshot.delete() } catch (_: Exception) {}
            }
        }
        Timber.i("CallUploadWorker ok=%d failed=%d deferred=%d", ok, failed, deferred)
        // 미뤄둔 (아직 저장 중) 게 있거나, 전부 실패면 재시도 예약
        return if (deferred > 0 || (failed > 0 && ok == 0)) Result.retry() else Result.success()
    }
}
