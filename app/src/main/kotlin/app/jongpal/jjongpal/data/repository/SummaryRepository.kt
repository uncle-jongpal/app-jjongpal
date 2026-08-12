package app.jongpal.jjongpal.data.repository

import app.jongpal.jjongpal.auth.TokenManager
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
    private val tokenManager: TokenManager,
    private val eventDao: app.jongpal.jjongpal.data.local.EventDao,
) {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)
    private val anyListType = Types.newParameterizedType(List::class.java, mapType)
    private val anyListAdapter = moshi.adapter<List<Map<String, Any?>>>(anyListType)

    fun recent(): Flow<List<SummaryEntity>> = dao.recent()

    fun recentByUser(userId: Int): Flow<List<SummaryEntity>> = dao.recentByUser(userId)

    suspend fun pullFromServer(): Int {
        return try {
            val resp = pcApi.listSummaries(userIdEq = currentUserFilter())
            if (!resp.isSuccessful) {
                Timber.w("listSummaries failed ${resp.code()}")
                return 0
            }
            val items = resp.body() ?: return 0

            // 통화 원문(받아쓰기) + 문장별 시간 정보 같이 받아오기 — "이 문장부터 듣기" 기능용
            val trByEvent = HashMap<String, Pair<String?, String?>>()
            try {
                val tr = pcApi.listTranscripts()
                if (tr.isSuccessful) {
                    tr.body()?.forEach { t ->
                        val ev = t.event_id ?: return@forEach
                        val segs = t.segments_json?.let { runCatching { anyListAdapter.toJson(it) }.getOrNull() }
                        trByEvent[ev] = (t.text to segs)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "원문 받아오기 실패 — 요약만 갱신")
            }

            // 폰에 남아 있는 녹음 파일 경로 (있으면 재생 가능)
            val audioByEvent = HashMap<String, String>()
            try {
                eventDao.callsWithFile().forEach { ev ->
                    val path = runCatching { mapAdapter.fromJson(ev.metadataJson ?: "{}") }
                        .getOrNull()?.get("file_path") as? String
                    if (!path.isNullOrBlank()) audioByEvent[ev.id] = path
                }
            } catch (e: Exception) {
                Timber.w(e, "녹음 경로 조회 실패")
            }

            val entities = items.map { dto ->
                val e = dto.toEntity()
                val tr = dto.event_id?.let { trByEvent[it] }
                e.copy(
                    transcriptText = tr?.first,
                    segmentsJson = tr?.second,
                    audioPath = dto.event_id?.let { audioByEvent[it] },
                )
            }
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

    // 통화 event 의 (id → 전화번호) 맵. 번호 검색용. metadata_json.phone 에서 추출.
    suspend fun callPhones(): Map<String, String> {
        return try {
            val resp = pcApi.listCallEventMeta(userIdEq = currentUserFilter())
            if (!resp.isSuccessful) return emptyMap()
            val out = HashMap<String, String>()
            resp.body()?.forEach { row ->
                val phone = row.metadata_json?.get("phone") as? String
                if (!phone.isNullOrBlank()) out[row.id] = phone
            }
            out
        } catch (e: Exception) {
            Timber.w(e, "callPhones load failed")
            emptyMap()
        }
    }

    // 보관함 핀 토글 — 로컬 먼저 반영(낙관적), 서버에도 PATCH 시도
    suspend fun setPinned(id: String, pinned: Boolean) {
        dao.setPinned(id, pinned)
        try {
            pcApi.patchSummary(idEq = "eq.$id", patch = app.jongpal.jjongpal.data.remote.SummaryPatch(pinned = pinned))
        } catch (e: Exception) {
            Timber.e(e, "setPinned push failed")
        }
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
            pinned = pinned,
        )

    // ISO 8601 파싱 — 소수점초 없음/마이크로초/오프셋(+09:00·Z·-07:00) 모두 허용.
    private fun parseIso(s: String): Long? = try {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try { java.time.Instant.parse(s).toEpochMilli() } catch (e2: Exception) { null }
    }

    // user_id 필터 결정.
    // 어드민 + "모든 사용자 보기" 켬 → null (RLS 어드민 권한으로 전체 노출)
    // 그 외 → "eq.<본인 id>" (본인 데이터만)
    private fun currentUserFilter(): String? {
        val isAdmin = tokenManager.userRole == "admin"
        if (isAdmin && tokenManager.showAllUsersForAdmin) return null
        val uid = tokenManager.userId
        return if (uid > 0) "eq.$uid" else null
    }
}
