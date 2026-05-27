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
    }

    override suspend fun doWork(): Result {
        if (!tokenManager.hasValidSession()) return Result.success()

        val pending = eventDao.pendingForSync()
            .filter { it.type == "call" }
        if (pending.isEmpty()) return Result.success()

        var ok = 0
        var failed = 0
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

            try {
                val body = file.asRequestBody("audio/m4a".toMediaTypeOrNull())
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
            }
        }
        Timber.i("CallUploadWorker ok=%d failed=%d", ok, failed)
        return if (failed > 0 && ok == 0) Result.retry() else Result.success()
    }
}
