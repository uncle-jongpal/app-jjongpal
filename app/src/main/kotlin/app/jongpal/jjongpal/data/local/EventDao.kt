package app.jongpal.jjongpal.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    @Update
    suspend fun update(event: EventEntity)

    // 동기화 대기 + 일시 실패 (재시도 가능) 이벤트. 재시도 한도 (maxRetry) 초과 시 영구 실패로 간주, 다시 안 뽑음.
    // phoneClassification='send' 인 것만 (skip_noise / skip_sensitive 는 PC 로 안 보냄)
    @Query("""
        SELECT * FROM events
        WHERE syncStatus IN ('PENDING', 'FAILED') AND retryCount < :maxRetry
          AND phoneClassification = 'send'
        ORDER BY timestamp ASC LIMIT :limit
    """)
    suspend fun pendingForSync(limit: Int = 50, maxRetry: Int = 5): List<EventEntity>

    // 메인 화면 "원본 알림" 탭 — 잡음 / 민감 분류 항목 숨김.
    @Query("""
        SELECT * FROM events
        WHERE phoneClassification NOT IN ('skip_noise', 'skip_sensitive')
        ORDER BY timestamp DESC LIMIT :limit
    """)
    fun recent(limit: Int = 200): Flow<List<EventEntity>>

    // 캡처 시점 dedup — 같은 출처 / 본문이 짧은 시간 안 (windowMs) 들어왔는지 확인.
    // 음악 앱 / 게임 등 알림 갱신 반복 막음.
    @Query("""
        SELECT COUNT(*) FROM events
        WHERE sourcePackage = :pkg
          AND COALESCE(title,'') = COALESCE(:title,'')
          AND COALESCE(content,'') = COALESCE(:content,'')
          AND timestamp >= :sinceMs
        LIMIT 1
    """)
    suspend fun countRecentDuplicate(pkg: String?, title: String?, content: String?, sinceMs: Long): Int

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun get(id: String): EventEntity?

    @Query("UPDATE events SET syncStatus = :status, syncedAt = :syncedAt, lastError = :error, retryCount = retryCount + :retryDelta WHERE id = :id")
    suspend fun updateSync(id: String, status: String, syncedAt: Long?, error: String?, retryDelta: Int)

    @Query("SELECT COUNT(*) FROM events WHERE syncStatus = :status")
    fun countByStatus(status: String): Flow<Int>
}
