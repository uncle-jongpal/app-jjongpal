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
import app.jongpal.jjongpal.util.ErrorContext
import app.jongpal.jjongpal.util.ErrorMessages
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        // 이 횟수만큼 연속 실패해야 실패 알림을 띄운다 — 한두 번 끊기는 건 다음 차례에 알아서 올라감.
        private const val NOTIFY_AFTER_FAILS = 3

        // 즉시 업로드(call-upload-now)와 정기 업로드(call-upload-periodic)가 동시에 돌면 같은 파일을
        // 두 번 동시에 보내다 서로 끊었다(2026-10-12 터널 기록: 같은 초에 끊김 2건씩). 한 번에 하나만.
        private val uploadLock = Mutex()
    }

    override suspend fun doWork(): Result = uploadLock.withLock { uploadPending() }

    private suspend fun uploadPending(): Result {
        if (!tokenManager.hasValidSession()) return Result.success()

        // 앱이 업로드 도중 강제 종료되면 사본이 남는다 — 잠금 안이라 지금 쓰는 사본은 없으니 전부 정리.
        applicationContext.cacheDir.listFiles { f -> f.name.startsWith("callup_") }?.forEach { it.delete() }

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
        var failed = 0          // 연속 실패가 쌓여 알림까지 띄운 것
        var quietFailed = 0     // 이번엔 실패했지만 아직 알릴 단계가 아닌 것(다음 차례 재시도)
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
                if (maybeNotifyFailure(notifier, event.retryCount + 1, event.id, "녹음 파일 위치 정보 없음")) failed++ else quietFailed++
                continue
            }
            val file = File(filePath)
            if (!file.exists()) {
                eventDao.updateSync(event.id, "FAILED", null, "file missing", 1)
                if (maybeNotifyFailure(notifier, event.retryCount + 1, file.name, "녹음 파일이 폰에서 사라짐")) failed++ else quietFailed++
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
            // 사본 이름에 작업 id 를 붙여, 혹시 다른 업로드 작업과 겹쳐도 서로의 사본을 덮어쓰거나 지우지 않게.
            val snapshot = File(applicationContext.cacheDir, "callup_${event.id}_${id}.m4a")
            var sentBytes = 0L
            var totalBytes = 0L
            try {
                uploadIndex++
                notifier.preparing(file.name)
                file.copyTo(snapshot, overwrite = true)
                totalBytes = snapshot.length()
                val totalMb = totalBytes / 1048576.0
                val body = ProgressRequestBody(snapshot, "audio/m4a".toMediaTypeOrNull()) { sent, total ->
                    sentBytes = sent
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
                    // 원본 코드는 로그·DB 에만. 사용자 알림엔 중앙 매퍼가 만든 친절한 문구만.
                    Timber.w("upload failed status=%d for %s", resp.code(), file.name)
                    eventDao.updateSync(event.id, "FAILED", null, "upload ${resp.code()}", 1)
                    val detail = ErrorMessages.uploadDetailForCode(resp.code())
                    if (maybeNotifyFailure(notifier, event.retryCount + 1, file.name, detail)) failed++ else quietFailed++
                }
            } catch (e: CancellationException) {
                // 안드로이드가 작업을 멈춤(배터리·네트워크 전환·실행 시간 한도) — 한 번은 알림 없이 다음 차례에.
                Timber.i("upload stopped by system for %s", file.name)
                withContext(NonCancellable) { recordSystemStop(notifier, event.id, event.retryCount, file.name) }
                throw e
            } catch (e: Exception) {
                if (isStopped) {
                    // 작업 중단으로 전송이 끊긴 경우(IOException "Canceled" 로 올라옴) — 위와 같음.
                    Timber.i("upload interrupted by stop for %s", file.name)
                    withContext(NonCancellable) { recordSystemStop(notifier, event.id, event.retryCount, file.name) }
                    return Result.retry()
                }
                Timber.e(e, "upload error for %s", filePath)
                val err = e.message ?: e.javaClass.simpleName
                eventDao.updateSync(event.id, "FAILED", null, err, 1)
                var detail = ErrorMessages.uploadDetail(e)
                if (totalBytes > 0 && sentBytes > 0) {
                    detail += " (%.1fMB 중 %.1fMB 보냄)".format(totalBytes / 1048576.0, sentBytes / 1048576.0)
                }
                if (maybeNotifyFailure(notifier, event.retryCount + 1, file.name, detail)) failed++ else quietFailed++
            } finally {
                try { snapshot.delete() } catch (_: Exception) {}
            }
        }
        Timber.i("CallUploadWorker ok=%d failed=%d quietFailed=%d deferred=%d", ok, failed, quietFailed, deferred)
        // 조용한 실패는 "대기" 로 표시 — 다음 차례에 다시 올린다.
        notifier.done(ok, failed, deferred + quietFailed)
        // 미뤄둔 (아직 저장 중) 게 있거나, 전부 실패면 재시도 예약
        val anyFailed = failed + quietFailed
        return if (deferred > 0 || (anyFailed > 0 && ok == 0)) Result.retry() else Result.success()
    }

    /**
     * 폰이 업로드 작업을 중단시킨 것도 횟수는 센다 — 큰 녹음이 매번 실행 시간 한도(약 10분)에 걸려
     * 끝없이 조용히 미뤄지는 걸 막기 위함. 알림은 실패와 같은 기준(연속 3번)으로만.
     * 중단 이유(시간 한도·절전·네트워크 전환)는 앱이 알 수 없어 문구는 중립적으로.
     */
    private suspend fun recordSystemStop(notifier: UploadNotifier, eventId: String, prevCount: Int, name: String) {
        notifier.clearProgress()
        eventDao.updateSync(eventId, "FAILED", null, "stopped by system", 1)
        maybeNotifyFailure(notifier, prevCount + 1, name, "업로드 도중 폰이 작업을 중단시킴 (오래 걸림·절전·네트워크 전환 등)")
    }

    /**
     * 연속 실패 횟수가 [NOTIFY_AFTER_FAILS] 이상일 때만 실패 알림을 띄운다. 띄웠으면 true.
     * 원인은 구체적으로(연결 끊김·시간 초과·서버 오류 코드·보낸 양) 보여준다.
     */
    private fun maybeNotifyFailure(notifier: UploadNotifier, failCount: Int, name: String, detail: String): Boolean {
        if (failCount < NOTIFY_AFTER_FAILS) return false
        notifier.failed(name, "$detail · ${failCount}번 연속 실패")
        return true
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
