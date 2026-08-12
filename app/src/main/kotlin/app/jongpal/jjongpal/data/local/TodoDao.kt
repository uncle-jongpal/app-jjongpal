package app.jongpal.jjongpal.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(todo: TodoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(todos: List<TodoEntity>)

    @Query("SELECT * FROM todos WHERE status IN ('open', 'done') ORDER BY (status = 'open') DESC, COALESCE(dueAt, createdAt) ASC")
    fun activeStream(): Flow<List<TodoEntity>>

    // 본인 계정만 — 어드민 '모두 보기' 아닐 때. 로컬에 다른 계정 데이터가 섞여 있어도 화면엔 본인 것만 보이게.
    @Query("SELECT * FROM todos WHERE userId = :userId AND status IN ('open', 'done') ORDER BY (status = 'open') DESC, COALESCE(dueAt, createdAt) ASC")
    fun activeStreamByUser(userId: Int): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE status = 'suggested' ORDER BY priority DESC, createdAt DESC")
    fun suggestionsStream(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE userId = :userId AND status = 'suggested' ORDER BY priority DESC, createdAt DESC")
    fun suggestionsStreamByUser(userId: Int): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE status = 'parked' ORDER BY priority DESC, createdAt DESC")
    fun parkedStream(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE userId = :userId AND status = 'parked' ORDER BY priority DESC, createdAt DESC")
    fun parkedStreamByUser(userId: Int): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun get(id: String): TodoEntity?

    @Query("UPDATE todos SET status = :status, completedAt = :completedAt, updatedAt = :now, syncStatus = 'PENDING' WHERE id = :id")
    suspend fun setStatus(id: String, status: String, completedAt: Long?, now: Long = System.currentTimeMillis())

    // 일괄 처리 — 여러 항목을 한 번에 (날짜별 전체 완료 등). syncStatus 'PENDING' 으로 서버 동기화 예약.
    @Query("UPDATE todos SET status = :status, completedAt = :completedAt, updatedAt = :now, syncStatus = 'PENDING' WHERE id IN (:ids)")
    suspend fun setStatusMany(ids: List<String>, status: String, completedAt: Long?, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM todos WHERE syncStatus = 'PENDING' ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun pendingForSync(limit: Int = 50): List<TodoEntity>

    @Query("SELECT id FROM todos WHERE syncStatus = 'PENDING'")
    suspend fun pendingIds(): List<String>

    @Query("UPDATE todos SET syncStatus = 'SYNCED', serverUpdatedAt = :serverUpdatedAt WHERE id = :id")
    suspend fun markSynced(id: String, serverUpdatedAt: Long)
}
