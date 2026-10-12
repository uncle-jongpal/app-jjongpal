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

    /**
     * 통화 검색 — 최근 100건이 아니라 로컬 표 전체에서 찾는다(예전엔 목록과 같은 100건 안에서만 찾았다).
     * userId 가 null 이면 모든 사용자(어드민 모두 보기). 번호 검색은 번호가 맞는 통화 event id 목록으로.
     */
    @Query("""
        SELECT * FROM summaries
        WHERE (:userId IS NULL OR userId = :userId)
          AND (
            summary LIKE '%' || :kw || '%'
            OR rawJson LIKE '%' || :kw || '%'
            OR transcriptText LIKE '%' || :kw || '%'
            OR eventId IN (:phoneEventIds)
          )
        ORDER BY createdAt DESC
    """)
    fun search(kw: String, userId: Int?, phoneEventIds: List<String>): Flow<List<SummaryEntity>>

    @Query("SELECT * FROM summaries WHERE id = :id")
    suspend fun get(id: String): SummaryEntity?

    // 서버에서 받은 요약과 합칠 로컬 행 — 원문·재생 경로·읽음 표시를 덮어쓰지 않게 하기 위함
    @Query("SELECT * FROM summaries WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<SummaryEntity>

    @Query("UPDATE summaries SET viewed = 1 WHERE id = :id")
    suspend fun markViewed(id: String)

    @Query("UPDATE summaries SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)
}
