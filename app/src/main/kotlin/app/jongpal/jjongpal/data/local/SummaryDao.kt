package app.jongpal.jjongpal.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: SummaryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SummaryEntity>)

    @Query("SELECT * FROM summaries ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int = 100): Flow<List<SummaryEntity>>

    @Query("SELECT * FROM summaries WHERE id = :id")
    suspend fun get(id: String): SummaryEntity?

    @Query("UPDATE summaries SET viewed = 1 WHERE id = :id")
    suspend fun markViewed(id: String)
}
