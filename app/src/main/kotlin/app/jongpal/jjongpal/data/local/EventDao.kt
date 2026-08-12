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

    /** 폰에 녹음 파일이 남아 있는 통화들 — 요약 화면에서 원본 재생용 */
    @Query("SELECT * FROM events WHERE type='call' AND metadataJson LIKE '%file_path%'")
    suspend fun callsWithFile(): List<EventEntity>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun get(id: String): EventEntity?

    @Query("UPDATE events SET syncStatus = :status, syncedAt = :syncedAt, lastError = :error, retryCount = retryCount + :retryDelta WHERE id = :id")
    suspend fun updateSync(id: String, status: String, syncedAt: Long?, error: String?, retryDelta: Int)

    // 업로드 중 파일이 자라나 크기 불일치로 실패 후 재시도 소진된 통화 재등록 (파일 안정 후 재업로드용).
    // 재등록되면 syncStatus 가 PENDING 으로 바뀌어 이 조건에서 빠지므로 반복 등록 안 됨.
    @Query("UPDATE events SET syncStatus = 'PENDING', retryCount = 0, lastError = NULL WHERE type = 'call' AND syncStatus = 'FAILED' AND lastError LIKE '%bytes but received%'")
    suspend fun requeueSizeMismatchCalls(): Int

    /**
     * 업로드에 실패해 재시도 한도를 소진한 통화를 다시 대기 상태로 되돌린다.
     *
     * 통화는 개수가 적고 하나하나가 소중하므로 **영구 포기하지 않는다.**
     * (2026-07-30 83MB 녹음이 시간 초과로 6번 실패한 뒤 그대로 묻힌 사례)
     * 단 파일 자체가 없는 경우는 되살려도 소용없으므로 제외한다.
     */
    @Query("""
        UPDATE events SET syncStatus = 'PENDING', retryCount = 0, lastError = NULL
        WHERE type = 'call' AND syncStatus = 'FAILED'
          AND COALESCE(lastError,'') NOT LIKE '%file missing%'
          AND COALESCE(lastError,'') NOT LIKE '%no file_path%'
    """)
    suspend fun requeueFailedCalls(): Int

    /** 업로드 못 한 통화 수 — 앱 화면에 실패가 보이게 하기 위함 */
    @Query("SELECT COUNT(*) FROM events WHERE type='call' AND syncStatus != 'SYNCED'")
    fun countUnsyncedCalls(): Flow<Int>

    @Query("SELECT COUNT(*) FROM events WHERE syncStatus = :status")
    fun countByStatus(status: String): Flow<Int>
}
