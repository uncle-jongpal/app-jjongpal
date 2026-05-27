package app.jongpal.jjongpal.capture.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import app.jongpal.jjongpal.auth.TokenManager

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var tokenManager: TokenManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (!tokenManager.hasValidSession()) return
        FileObserverService.start(context)
        // 부팅 후 누락된 통화 녹음 일괄 검사
        CallSweepWorker.runOnce(context)
    }
}
