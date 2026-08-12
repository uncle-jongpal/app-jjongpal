package app.jongpal.jjongpal.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        EventEntity::class,
        TodoEntity::class,
        SummaryEntity::class,
        AppointmentEntity::class,
    ],
    version = 6,                              // v6: summaries 에 원문·문장별 시간정보·녹음 경로 컬럼
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    companion object {
        /**
         * v5 → v6 : 통화 원문과 문장별 시간 정보를 담을 칸 추가.
         * (기존 기록을 지우지 않고 칸만 더한다 — 업로드 상태가 날아가면 안 되므로)
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE summaries ADD COLUMN segmentsJson TEXT")
                db.execSQL("ALTER TABLE summaries ADD COLUMN audioPath TEXT")
            }
        }
    }

    abstract fun eventDao(): EventDao
    abstract fun todoDao(): TodoDao
    abstract fun summaryDao(): SummaryDao
    abstract fun appointmentDao(): AppointmentDao
}
