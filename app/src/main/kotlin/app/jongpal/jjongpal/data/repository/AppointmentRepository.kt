package app.jongpal.jjongpal.data.repository

import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.data.local.AppointmentDao
import app.jongpal.jjongpal.data.local.AppointmentEntity
import app.jongpal.jjongpal.data.remote.AppointmentDto
import app.jongpal.jjongpal.data.remote.AppointmentPatch
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
    private val tokenManager: TokenManager,
) {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun upcoming(): Flow<List<AppointmentEntity>> = dao.upcoming()

    // 받은 정리함 제안 → 약속으로 확정 (로컬 생성). 서버 push 경로는 아직 없음 — 로컬에만 남음.
    suspend fun createLocal(
        userId: Int,
        title: String,
        startAt: Long,
        sourceEventId: String?,
        sourceExcerpt: String?,
        withPerson: String?,
    ) {
        dao.upsert(
            AppointmentEntity(
                id = java.util.UUID.randomUUID().toString(),
                userId = userId,
                sourceEventId = sourceEventId,
                sourceExcerpt = sourceExcerpt,
                title = title,
                startAt = startAt,
                endAt = null,
                location = null,
                withPerson = withPerson,
                confidence = 1f,
                confirmed = true,
            )
        )
    }

    // 약속 확정 — 로컬 먼저 반영(낙관적), 서버에도 PATCH 시도
    suspend fun confirm(id: String) {
        dao.setConfirmed(id, true)
        try {
            pcApi.patchAppointment(idEq = "eq.$id", patch = AppointmentPatch(confirmed = true))
        } catch (e: Exception) {
            Timber.e(e, "confirm appointment push failed")
        }
    }

    suspend fun pullFromServer(): Int {
        return try {
            val resp = pcApi.listAppointments(userIdEq = currentUserFilter())
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
            sourceExcerpt = source_excerpt,
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

    // user_id 필터 — 어드민 + "모두 보기" 모드만 null, 그 외 본인 id 박음.
    private fun currentUserFilter(): String? {
        val isAdmin = tokenManager.userRole == "admin"
        if (isAdmin && tokenManager.showAllUsersForAdmin) return null
        val uid = tokenManager.userId
        return if (uid > 0) "eq.$uid" else null
    }
}
