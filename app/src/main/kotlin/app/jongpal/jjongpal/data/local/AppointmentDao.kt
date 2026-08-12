package app.jongpal.jjongpal.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppointmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(appt: AppointmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AppointmentEntity>)

    @Query("SELECT * FROM appointments WHERE startAt >= :sinceMs ORDER BY startAt ASC LIMIT :limit")
    fun upcoming(sinceMs: Long = System.currentTimeMillis(), limit: Int = 100): Flow<List<AppointmentEntity>>

    // 본인 계정만 — 어드민 '모두 보기' 아닐 때.
    @Query("SELECT * FROM appointments WHERE userId = :userId AND startAt >= :sinceMs ORDER BY startAt ASC LIMIT :limit")
    fun upcomingByUser(userId: Int, sinceMs: Long = System.currentTimeMillis(), limit: Int = 100): Flow<List<AppointmentEntity>>

    // 지난 약속까지 포함 — 필터 "지난 것 포함" 용
    @Query("SELECT * FROM appointments ORDER BY startAt ASC LIMIT :limit")
    fun all(limit: Int = 300): Flow<List<AppointmentEntity>>

    @Query("SELECT * FROM appointments WHERE userId = :userId ORDER BY startAt ASC LIMIT :limit")
    fun allByUser(userId: Int, limit: Int = 300): Flow<List<AppointmentEntity>>

    @Query("UPDATE appointments SET confirmed = :confirmed WHERE id = :id")
    suspend fun setConfirmed(id: String, confirmed: Boolean)

    @Query("DELETE FROM appointments WHERE id = :id")
    suspend fun deleteById(id: String)
}
