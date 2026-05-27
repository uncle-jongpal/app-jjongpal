package app.jongpal.jjongpal

import android.app.Application
import androidx.work.Configuration
import app.jongpal.jjongpal.capture.call.CallSweepWorker
import app.jongpal.jjongpal.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class JjongpalApp : Application(), Configuration.Provider {

    @Inject lateinit var workConfiguration: Configuration
    @Inject lateinit var appSyncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = workConfiguration

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // 통화 녹음 일괄 검사 — 안드로이드 10+ 스코프드 스토리지 정책상 다른 앱이 만든 파일 알림 누락 대비
        CallSweepWorker.schedulePeriodic(this)
        CallSweepWorker.runOnce(this)
    }
}
