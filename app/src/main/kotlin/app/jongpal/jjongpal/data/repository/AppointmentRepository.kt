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

    fun upcomingByUser(userId: Int): Flow<List<AppointmentEntity>> = dao.upcomingByUser(userId)

    // 지난 약속까지 — 화면에서 필터링해서 보여줌
    fun all(): Flow<List<AppointmentEntity>> = dao.all()

    fun allByUser(userId: Int): Flow<List<AppointmentEntity>> = dao.allByUser(userId)

    // 약속 삭제 — 로컬 먼저 지우고(낙관적) 서버에도 삭제 시도
    suspend fun delete(id: String) {
        dao.deleteById(id)
        try {
            pcApi.deleteAppointment(idEq = "eq.$id")
        } catch (e: Exception) {
            Timber.e(e, "delete appointment push failed")
        }
    }

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

    // ISO 8601 파싱 — 소수점초 없음/마이크로초/오프셋(+09:00·Z·-07:00) 모두 허용.
    // (예전 isoFormat 은 소수점초 .SSS 를 필수로 요구 → 정시 약속이 전부 파싱 실패해 현재시각으로 망가졌음)
    private fun parseIso(s: String): Long? = try {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try { java.time.Instant.parse(s).toEpochMilli() } catch (e2: Exception) { null }
    }

    // user_id 필터 — 어드민 + "모두 보기" 모드만 null, 그 외 본인 id 박음.
    private fun currentUserFilter(): String? {
        val isAdmin = tokenManager.userRole == "admin"
        if (isAdmin && tokenManager.showAllUsersForAdmin) return null
        val uid = tokenManager.userId
        return if (uid > 0) "eq.$uid" else null
    }
}
