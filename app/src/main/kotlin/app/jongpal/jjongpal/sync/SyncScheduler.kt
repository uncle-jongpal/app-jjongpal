package app.jongpal.jjongpal.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** [fullPull] = 요약 전체 받기(당겨서 새로고침). 평소엔 최근 것만. */
    fun scheduleNow(fullPull: Boolean = false) {
        val sync = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint)
            .setInputData(workDataOf(SyncWorker.KEY_FULL_PULL to fullPull))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sync-now", ExistingWorkPolicy.REPLACE, sync,
        )
        val callUp = OneTimeWorkRequestBuilder<CallUploadWorker>()
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "call-upload-now", ExistingWorkPolicy.APPEND_OR_REPLACE, callUp,
        )
    }

    fun schedulePeriodic() {
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "sync-periodic", ExistingPeriodicWorkPolicy.UPDATE, periodic,
        )

        val periodicCalls = PeriodicWorkRequestBuilder<CallUploadWorker>(20, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "call-upload-periodic", ExistingPeriodicWorkPolicy.UPDATE, periodicCalls,
        )
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork("sync-now")
        WorkManager.getInstance(context).cancelUniqueWork("sync-periodic")
        WorkManager.getInstance(context).cancelUniqueWork("call-upload-now")
        WorkManager.getInstance(context).cancelUniqueWork("call-upload-periodic")
    }
}
