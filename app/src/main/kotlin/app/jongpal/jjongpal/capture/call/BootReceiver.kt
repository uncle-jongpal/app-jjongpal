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
        // 부팅 완료 + 앱 업데이트(MY_PACKAGE_REPLACED) 후 — 업데이트하면 감시 서비스가 죽은 채로 남았다
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!tokenManager.hasValidSession()) return
        FileObserverService.start(context)
        // 부팅·업데이트 사이 누락된 통화 녹음 일괄 검사
        CallSweepWorker.runOnce(context)
    }
}
