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

    // 본인 데이터만 — 어드민 + 모든 사용자 보기 꺼짐 + 일반 사용자 케이스
    @Query("SELECT * FROM summaries WHERE userId = :userId ORDER BY createdAt DESC LIMIT :limit")
    fun recentByUser(userId: Int, limit: Int = 100): Flow<List<SummaryEntity>>

    @Query("SELECT * FROM summaries WHERE id = :id")
    suspend fun get(id: String): SummaryEntity?

    @Query("UPDATE summaries SET viewed = 1 WHERE id = :id")
    suspend fun markViewed(id: String)
}
