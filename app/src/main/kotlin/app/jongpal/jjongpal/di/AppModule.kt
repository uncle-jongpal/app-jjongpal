package app.jongpal.jjongpal.di

import android.content.Context
import androidx.room.Room
import app.jongpal.jjongpal.data.local.AppDatabase
import app.jongpal.jjongpal.data.local.AppointmentDao
import app.jongpal.jjongpal.data.local.EventDao
import app.jongpal.jjongpal.data.local.SummaryDao
import app.jongpal.jjongpal.data.local.TodoDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "jjongpal.db")
            .addMigrations(AppDatabase.MIGRATION_5_6)
            // 기존 기록(업로드 상태 등)이 날아가면 안 되므로 파괴적 초기화는 쓰지 않는다
            .build()

    @Provides fun provideEventDao(db: AppDatabase): EventDao = db.eventDao()
    @Provides fun provideTodoDao(db: AppDatabase): TodoDao = db.todoDao()
    @Provides fun provideSummaryDao(db: AppDatabase): SummaryDao = db.summaryDao()
    @Provides fun provideAppointmentDao(db: AppDatabase): AppointmentDao = db.appointmentDao()
}
