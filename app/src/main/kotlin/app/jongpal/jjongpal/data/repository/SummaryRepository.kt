package app.jongpal.jjongpal.data.repository

import app.jongpal.jjongpal.data.local.SummaryDao
import app.jongpal.jjongpal.data.local.SummaryEntity
import app.jongpal.jjongpal.data.remote.PcApi
import app.jongpal.jjongpal.data.remote.SummaryDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SummaryRepository @Inject constructor(
    private val dao: SummaryDao,
    private val pcApi: PcApi,
    private val moshi: Moshi,
) {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)

    fun recent(): Flow<List<SummaryEntity>> = dao.recent()

    suspend fun pullFromServer(): Int {
        return try {
            val resp = pcApi.listSummaries()
            if (!resp.isSuccessful) {
                Timber.w("listSummaries failed ${resp.code()}")
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

    suspend fun markViewed(id: String) {
        dao.markViewed(id)
    }

    private fun SummaryDto.toEntity(): SummaryEntity =
        SummaryEntity(
            id = id,
            eventId = event_id,
            userId = user_id,
            transcriptText = null,
            summary = summary,
            rawJson = raw_json?.let { mapAdapter.toJson(it) } ?: "{}",
            createdAt = parseIso(created_at) ?: System.currentTimeMillis(),
        )

    private fun parseIso(s: String): Long? = try {
        isoFormat.parse(s)?.time
    } catch (e: Exception) {
        null
    }
}
