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

    @Query("SELECT * FROM todos WHERE status != 'archived' ORDER BY (status = 'open') DESC, COALESCE(dueAt, createdAt) ASC")
    fun activeStream(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun get(id: String): TodoEntity?

    @Query("UPDATE todos SET status = :status, completedAt = :completedAt, updatedAt = :now, syncStatus = 'PENDING' WHERE id = :id")
    suspend fun setStatus(id: String, status: String, completedAt: Long?, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM todos WHERE syncStatus = 'PENDING' ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun pendingForSync(limit: Int = 50): List<TodoEntity>

    @Query("UPDATE todos SET syncStatus = 'SYNCED', serverUpdatedAt = :serverUpdatedAt WHERE id = :id")
    suspend fun markSynced(id: String, serverUpdatedAt: Long)
}
