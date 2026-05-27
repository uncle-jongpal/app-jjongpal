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
}
