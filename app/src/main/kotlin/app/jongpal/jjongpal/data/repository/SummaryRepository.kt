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

    /**
     * 서버 요약을 받아 로컬에 합친다.
     * - 정기 동기화는 최근 [PERIODIC_LIMIT]건만(15분마다 2000건씩 받던 낭비 제거)
     * - 사용자가 당겨서 새로고침하면 [FULL_LIMIT]건 전체
     * - 서버 응답 실패(2xx 아님·연결 실패)는 null — 설정 화면 연결 표시등이 이걸 본다
     */
    suspend fun pullFromServer(limit: Int = PERIODIC_LIMIT): Int? {
        // 반환값: 받은 건수, 서버가 2xx 로 답하지 않았거나 연결 실패면 null.
        // (예전엔 실패도 0 으로 삼켜 설정의 "PC 연결" 표시등이 늘 초록이었다)
        val resp = try {
            pcApi.listSummaries(userIdEq = currentUserFilter(), limit = limit)
        } catch (e: Exception) {
            Timber.e(e, "pullFromServer error")
            return null
        }
        if (!resp.isSuccessful) {
            Timber.w("listSummaries failed ${resp.code()}")
            return null
        }
        val items = resp.body() ?: return 0
        // 로컬 저장 실패는 서버 연결 문제가 아니므로 연결 상태엔 반영하지 않음
        return try {
            saveFromServer(items, transcriptLimit = limit)
        } catch (e: Exception) {
            Timber.e(e, "summary local save error")
            0
        }
    }

    /**
     * 서버에서 받은 요약 목록을 로컬 DB 에 합쳐 넣는다(동기화·새 요약 알림 공용).
     *
     * 원문(받아쓰기)은 로컬에 아직 없는 요약이 있을 때만 받는다 — 예전엔 매번 원문 2000건(전문+문장 시간)을
     * 통째로 받았다. 서버 원문 목록은 통화별 필터를 지원하지 않아 최신순 [transcriptLimit]건을 받아 짝짓는다.
     * 덮어쓰기(REPLACE) 전에 로컬 행과 합쳐 원문·재생 경로·읽음 표시가 지워지지 않게 한다.
     */
    suspend fun saveFromServer(items: List<SummaryDto>, transcriptLimit: Int = PERIODIC_LIMIT): Int {
        if (items.isEmpty()) return 0
        val existing = HashMap<String, SummaryEntity>()
        // SQLite 변수 개수 한도 대비 나눠서 조회
        items.map { it.id }.chunked(500).forEach { chunk ->
            dao.getByIds(chunk).forEach { existing[it.id] = it }
        }

        // 원문을 받는 조건: 로컬에 처음 들어오는 요약이 있을 때. 전체 새로고침(큰 limit)일 땐 원문 빠진 요약도.
        // (서버에 원문이 아예 없는 요약 때문에 15분마다 원문 목록을 다시 받는 일이 없게 기존 행은 평소엔 제외)
        val full = transcriptLimit >= FULL_LIMIT
        val needTranscript = items.any { dto ->
            val local = existing[dto.id]
            dto.event_id != null && (local == null || (full && local.transcriptText == null))
        }
        val trByEvent = HashMap<String, Pair<String?, String?>>()
        if (needTranscript) {
            try {
                val tr = pcApi.listTranscripts(limit = transcriptLimit)
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
            mergeWithLocal(
                server = dto.toEntity(),
                local = existing[dto.id],
                transcript = dto.event_id?.let { trByEvent[it] },
                audioPath = dto.event_id?.let { audioByEvent[it] },
            )
        }
        dao.upsertAll(entities)
        return entities.size
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

    companion object {
        /** 정기 동기화로 받는 최근 요약 수 */
        const val PERIODIC_LIMIT = 100
        /** 사용자가 직접 새로고침할 때 받는 요약 수(예전 정기 동기화 값) */
        const val FULL_LIMIT = 2000
    }

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

/**
 * 서버 요약 + 로컬 행 합치기 (순수 함수 — 단위 테스트 대상).
 * 서버 값(요약·핀)이 기준이고, 서버가 안 주는 로컬 전용 값(원문·문장 시간·재생 경로·읽음)은 보존한다.
 */
internal fun mergeWithLocal(
    server: SummaryEntity,
    local: SummaryEntity?,
    transcript: Pair<String?, String?>?,
    audioPath: String?,
): SummaryEntity = server.copy(
    transcriptText = transcript?.first ?: local?.transcriptText,
    segmentsJson = transcript?.second ?: local?.segmentsJson,
    audioPath = audioPath ?: local?.audioPath,
    viewed = local?.viewed ?: false,
)
