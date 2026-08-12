package app.jongpal.jjongpal.data.repository

import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.data.local.TodoDao
import app.jongpal.jjongpal.data.local.TodoEntity
import app.jongpal.jjongpal.data.remote.PcApi
import app.jongpal.jjongpal.data.remote.TodoDto
import app.jongpal.jjongpal.data.remote.TodoPatch
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TodoRepository @Inject constructor(
    private val dao: TodoDao,
    private val pcApi: PcApi,
    private val tokenManager: TokenManager,
) {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun activeStream(): Flow<List<TodoEntity>> = dao.activeStream()
    fun activeStreamByUser(userId: Int): Flow<List<TodoEntity>> = dao.activeStreamByUser(userId)

    fun suggestionsStream(): Flow<List<TodoEntity>> = dao.suggestionsStream()
    fun suggestionsStreamByUser(userId: Int): Flow<List<TodoEntity>> = dao.suggestionsStreamByUser(userId)

    fun parkedStream(): Flow<List<TodoEntity>> = dao.parkedStream()
    fun parkedStreamByUser(userId: Int): Flow<List<TodoEntity>> = dao.parkedStreamByUser(userId)

    // 삼촌 제안 → 사용자가 '할 일로' 확정
    suspend fun accept(id: String) {
        dao.setStatus(id = id, status = "open", completedAt = null)
    }

    // 삼촌 제안 → 사용자가 '보류' (나중에 보려고 미뤄둠)
    suspend fun park(id: String) {
        dao.setStatus(id = id, status = "parked", completedAt = null)
    }

    // 보류함 → 다시 검토 목록(제안)으로 되돌림
    suspend fun unpark(id: String) {
        dao.setStatus(id = id, status = "suggested", completedAt = null)
    }

    // 삼촌 제안 → 사용자가 '넘기기'
    suspend fun dismiss(id: String) {
        dao.setStatus(id = id, status = "dismissed", completedAt = null)
    }

    suspend fun addManual(userId: Int, content: String, dueAt: Long? = null) {
        val now = System.currentTimeMillis()
        dao.upsert(
            TodoEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                content = content,
                source = "manual",
                sourceEventId = null,
                dueAt = dueAt,
                relatedPerson = null,
                status = "open",
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    suspend fun setDone(id: String, done: Boolean) {
        val now = System.currentTimeMillis()
        dao.setStatus(
            id = id,
            status = if (done) "done" else "open",
            completedAt = if (done) now else null,
        )
    }

    // 여러 할 일을 한 번에 완료 (날짜별 전체 완료 등)
    suspend fun setDoneMany(ids: List<String>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.setStatusMany(ids = ids, status = "done", completedAt = now, now = now)
    }

    suspend fun pullFromServer(): Int {
        return try {
            val resp = pcApi.listTodos(userIdEq = currentUserFilter())
            if (!resp.isSuccessful) {
                Timber.w("listTodos failed ${resp.code()}")
                return 0
            }
            val items = resp.body() ?: return 0
            // 아직 서버에 못 보낸 로컬 변경(PENDING)은 덮어쓰지 않음 — 완료 체크 유실 방지.
            val pending = dao.pendingIds().toHashSet()
            val entities = items.map { it.toEntity() }.filter { it.id !in pending }
            dao.upsertAll(entities)
            entities.size
        } catch (e: Exception) {
            Timber.e(e, "pullFromServer error")
            0
        }
    }

    suspend fun pushToServer(): Int {
        val pending = dao.pendingForSync()
        if (pending.isEmpty()) return 0
        return try {
            val dtos = pending.map { it.toDto() }
            val resp = pcApi.upsertTodos(dtos)
            if (resp.isSuccessful) {
                val now = System.currentTimeMillis()
                pending.forEach { dao.markSynced(it.id, now) }
                pending.size
            } else {
                Timber.w("upsertTodos failed ${resp.code()}")
                0
            }
        } catch (e: Exception) {
            Timber.e(e, "pushToServer error")
            0
        }
    }

    private fun TodoDto.toEntity(): TodoEntity {
        val now = System.currentTimeMillis()
        return TodoEntity(
            id = id,
            userId = user_id,
            content = content,
            source = source ?: "manual",
            sourceEventId = source_event_id,
            sourceExcerpt = source_excerpt,
            dueAt = due_at?.let { parseIso(it) },
            relatedPerson = related_person,
            status = status,
            priority = priority,
            completionConfidence = completion_confidence,
            createdAt = parseIso(created_at) ?: now,
            updatedAt = parseIso(updated_at) ?: now,
            completedAt = completed_at?.let { parseIso(it) },
            syncStatus = "SYNCED",
            serverUpdatedAt = parseIso(updated_at),
        )
    }

    private fun TodoEntity.toDto(): TodoDto = TodoDto(
        id = id,
        user_id = userId,
        content = content,
        source = source,
        source_event_id = sourceEventId,
        source_excerpt = sourceExcerpt,
        due_at = dueAt?.let { iso(it) },
        related_person = relatedPerson,
        status = status,
        priority = priority,
        completion_confidence = completionConfidence,
        created_at = iso(createdAt),
        updated_at = iso(updatedAt),
        completed_at = completedAt?.let { iso(it) },
    )

    // ISO 8601 파싱 — 소수점초 없음/마이크로초/오프셋(+09:00·Z·-07:00) 모두 허용.
    private fun parseIso(s: String): Long? = try {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try { java.time.Instant.parse(s).toEpochMilli() } catch (e2: Exception) { null }
    }

    private fun iso(ms: Long): String = isoFormat.format(java.util.Date(ms))

    // user_id 필터 — 어드민 + "모두 보기" 모드만 null, 그 외 본인 id 박음.
    private fun currentUserFilter(): String? {
        val isAdmin = tokenManager.userRole == "admin"
        if (isAdmin && tokenManager.showAllUsersForAdmin) return null
        val uid = tokenManager.userId
        return if (uid > 0) "eq.$uid" else null
    }
}
