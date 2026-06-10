package app.jongpal.jjongpal.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        EventEntity::class,
        TodoEntity::class,
        SummaryEntity::class,
        AppointmentEntity::class,
    ],
    version = 3,                              // v3: todos/appointments 에 sourceExcerpt 컬럼 추가
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun todoDao(): TodoDao
    abstract fun summaryDao(): SummaryDao
    abstract fun appointmentDao(): AppointmentDao
}
