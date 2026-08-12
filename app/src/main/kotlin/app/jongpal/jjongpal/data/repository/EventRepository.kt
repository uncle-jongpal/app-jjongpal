package app.jongpal.jjongpal.data.repository

import app.jongpal.jjongpal.data.local.EventDao
import app.jongpal.jjongpal.data.local.EventEntity
import app.jongpal.jjongpal.data.remote.EventDto
import app.jongpal.jjongpal.data.remote.PcApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventRepository @Inject constructor(
    private val dao: EventDao,
    private val pcApi: PcApi,
    private val moshi: Moshi,
) {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)

    suspend fun captureNotification(
        eventId: String,
        sourcePackage: String?,
        title: String?,
        content: String?,
        sender: String?,
        timestampMs: Long,
        phoneClassification: String = "send",
        phoneClassificationReason: String? = null,
    ) {
        dao.insert(
            EventEntity(
                id = eventId,
                type = "notification",
                sourcePackage = sourcePackage,
                title = title,
                content = content,
                sender = sender,
                timestamp = timestampMs,
                phoneClassification = phoneClassification,
                phoneClassificationReason = phoneClassificationReason,
            )
        )
    }

    suspend fun captureCallFile(
        eventId: String,
        filePath: String,
        durationSec: Int?,
        timestampMs: Long,
        phoneNumber: String? = null,
    ) {
        val meta = mapOf<String, Any?>(
            "file_path" to filePath,
            "duration_sec" to durationSec,
            "phone" to phoneNumber,
        )
        dao.insert(
            EventEntity(
                id = eventId,
                type = "call",
                sourcePackage = "android.call",
                title = null,
                content = null,
                sender = null,
                timestamp = timestampMs,
                metadataJson = mapAdapter.toJson(meta),
            )
        )
    }

    suspend fun syncPending(userId: Int, deviceId: String?): SyncResult {
        val batch = dao.pendingForSync(limit = 50)
        if (batch.isEmpty()) return SyncResult(0, 0)

        val notificationDtos = batch.filter { it.type == "notification" }.map { it.toDto(userId, deviceId) }
        var ok = 0
        var failed = 0

        if (notificationDtos.isNotEmpty()) {
            try {
                val resp = pcApi.postEvents(notificationDtos)
                if (resp.isSuccessful) {
                    val now = System.currentTimeMillis()
                    notificationDtos.forEach {
                        dao.updateSync(it.id, "SYNCED", now, null, 0)
                    }
                    ok += notificationDtos.size
                } else {
                    val err = "events post ${resp.code()}"
                    notificationDtos.forEach { dao.updateSync(it.id, "FAILED", null, err, 1) }
                    failed += notificationDtos.size
                }
            } catch (e: Exception) {
                Timber.e(e, "events post error")
                notificationDtos.forEach { dao.updateSync(it.id, "FAILED", null, e.message ?: "error", 1) }
                failed += notificationDtos.size
            }
        }

        // 통화 이벤트는 별도 흐름 — CallUploadWorker 가 처리. 여기선 알림만.
        return SyncResult(ok, failed)
    }

    private fun EventEntity.toDto(userId: Int, deviceId: String?): EventDto =
        EventDto(
            id = id,
            user_id = userId,
            device_id = deviceId,
            type = type,
            source_package = sourcePackage,
            title = title,
            content = content,
            timestamp = isoFormat.format(java.util.Date(timestamp)),
            device_timestamp = isoFormat.format(java.util.Date(timestamp)),
            metadata_json = metadataJson?.let { mapAdapter.fromJson(it) },
        )

    data class SyncResult(val ok: Int, val failed: Int)
}
