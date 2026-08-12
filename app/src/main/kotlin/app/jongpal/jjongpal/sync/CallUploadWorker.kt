package app.jongpal.jjongpal.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.data.local.EventDao
import app.jongpal.jjongpal.data.remote.EventMetaPatch
import app.jongpal.jjongpal.data.remote.PcApi
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
    private val pcApi: PcApi,
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
        // 시간 초과 등으로 재시도 한도를 소진한 통화도 되살린다 — 통화는 영구 포기하지 않는다.
        val revived = eventDao.requeueFailedCalls()
        if (revived > 0) Timber.i("업로드 실패했던 통화 %d건 재시도 대기로 되돌림", revived)
        if (requeued > 0) Timber.i("requeued %d size-mismatch calls", requeued)

        val pending = eventDao.pendingForSync()
            .filter { it.type == "call" }
        if (pending.isEmpty()) return Result.success()

        var ok = 0
        var failed = 0
        var deferred = 0
        val deviceId = tokenManager.deviceId
        val notifier = UploadNotifier(applicationContext)
        val totalToUpload = pending.size
        var uploadIndex = 0

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

            // 통화 녹음이 끝까지 완성됐는지(mp4 moov atom 존재) 확인 — 덜 끝난 토막을 올려
            // 깨진 파일이 서버에 박히는 것 방지. 완성 전이면 미루기(실패로 안 침). m4a/mp4 외 형식은 검사 생략.
            val nameLower = file.name.lowercase()
            val isMp4Family = nameLower.endsWith(".m4a") || nameLower.endsWith(".mp4")
            if (isMp4Family && !isFinalizedMp4(file)) {
                Timber.i("call not finalized yet (no moov), deferring %s", file.name)
                deferred++
                continue
            }

            // 안정된 사본을 떠서 업로드 — 업로드 도중 원본이 바뀌어도 선언 크기와 전송 크기가 어긋나지 않음.
            val snapshot = File(applicationContext.cacheDir, "callup_${event.id}.m4a")
            try {
                uploadIndex++
                notifier.preparing(file.name)
                file.copyTo(snapshot, overwrite = true)
                val totalMb = snapshot.length() / 1048576.0
                val body = ProgressRequestBody(snapshot, "audio/m4a".toMediaTypeOrNull()) { sent, total ->
                    val pct = if (total > 0) ((sent * 100) / total).toInt() else 0
                    notifier.progress(file.name, uploadIndex, totalToUpload, pct, sent / 1048576.0, totalMb)
                }
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
                    // 상대 전화번호가 있으면 서버 통화 event 의 metadata_json 에 채워 넣음(번호 검색용).
                    // 업로드로 막 생성된 event 라 비어 있음 → 본인 권한으로 PATCH. 실패해도 통화 동기화엔 영향 없음.
                    val phone = meta["phone"] as? String
                    if (!phone.isNullOrBlank()) {
                        try {
                            pcApi.patchEvent(
                                "eq.${event.id}",
                                EventMetaPatch(mapOf("phone" to phone, "duration_sec" to duration)),
                            )
                        } catch (e: Exception) {
                            Timber.w(e, "patch call phone failed for %s", event.id)
                        }
                    }
                    // 사용자 통화 녹음 원본은 절대 삭제하지 않음. 업로드는 사본만 보냄.
                    ok++
                } else {
                    val err = "upload ${resp.code()}"
                    eventDao.updateSync(event.id, "FAILED", null, err, 1)
                    notifier.failed(file.name, err)
                    failed++
                }
            } catch (e: Exception) {
                Timber.e(e, "upload error for %s", filePath)
                val err = e.message ?: e.javaClass.simpleName
                eventDao.updateSync(event.id, "FAILED", null, err, 1)
                notifier.failed(file.name, err)
                failed++
            } finally {
                try { snapshot.delete() } catch (_: Exception) {}
            }
        }
        Timber.i("CallUploadWorker ok=%d failed=%d deferred=%d", ok, failed, deferred)
        notifier.done(ok, failed, deferred)
        // 미뤄둔 (아직 저장 중) 게 있거나, 전부 실패면 재시도 예약
        return if (deferred > 0 || (failed > 0 && ok == 0)) Result.retry() else Result.success()
    }

    // mp4/m4a 최상위 박스를 훑어 'moov' 박스가 있으면 완성된(녹음 종료·최종화된) 파일로 간주.
    // 녹음 중 잘린 파일은 moov 가 없어 false → 업로드 보류. 박스 내용은 안 읽고 오프셋만 건너뜀.
    private fun isFinalizedMp4(f: File): Boolean {
        return try {
            val len = f.length()
            if (len < 8) return false
            java.io.RandomAccessFile(f, "r").use { raf ->
                var offset = 0L
                val header = ByteArray(8)
                while (offset + 8 <= len) {
                    raf.seek(offset)
                    raf.readFully(header)
                    var boxSize = ((header[0].toLong() and 0xFF) shl 24) or
                        ((header[1].toLong() and 0xFF) shl 16) or
                        ((header[2].toLong() and 0xFF) shl 8) or
                        (header[3].toLong() and 0xFF)
                    val type = String(header, 4, 4, Charsets.US_ASCII)
                    if (type == "moov") return@isFinalizedMp4 true
                    if (boxSize == 1L) {
                        // 64비트 확장 크기
                        val ext = ByteArray(8)
                        raf.readFully(ext)
                        boxSize = 0L
                        for (i in 0 until 8) boxSize = (boxSize shl 8) or (ext[i].toLong() and 0xFF)
                    }
                    if (boxSize < 8L) return@isFinalizedMp4 false
                    offset += boxSize
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
