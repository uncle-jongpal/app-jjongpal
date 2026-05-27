package app.jongpal.jjongpal.data.repository

import app.jongpal.jjongpal.data.local.AppointmentDao
import app.jongpal.jjongpal.data.local.AppointmentEntity
import app.jongpal.jjongpal.data.remote.AppointmentDto
import app.jongpal.jjongpal.data.remote.PcApi
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppointmentRepository @Inject constructor(
    private val dao: AppointmentDao,
    private val pcApi: PcApi,
) {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun upcoming(): Flow<List<AppointmentEntity>> = dao.upcoming()

    suspend fun pullFromServer(): Int {
        return try {
            val resp = pcApi.listAppointments()
            if (!resp.isSuccessful) {
                Timber.w("listAppointments failed ${resp.code()}")
                return 0
            }
            val items = resp.body() ?: return 0
            val entities = items.map { it.toEntity() }
            dao.upsertAll(entities)
            entities.size
        } catch (e: Exception) {
            Timber.e(e, "pullFromServer error")
            0
        }
    }

    private fun AppointmentDto.toEntity(): AppointmentEntity =
        AppointmentEntity(
            id = id,
            userId = user_id,
            sourceEventId = source_event_id,
            title = title,
            startAt = parseIso(start_at) ?: System.currentTimeMillis(),
            endAt = end_at?.let { parseIso(it) },
            location = location,
            withPerson = with_person,
            confidence = confidence,
            confirmed = confirmed,
            createdAt = parseIso(created_at) ?: System.currentTimeMillis(),
        )

    private fun parseIso(s: String): Long? = try {
        isoFormat.parse(s)?.time
    } catch (e: Exception) {
        null
    }
}
