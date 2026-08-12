package app.jongpal.jjongpal.capture.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import timber.log.Timber

/**
 * 통화 종료 신호 수신기.
 *
 * 매니페스트에 선언된 수신기라 **앱이 꺼져 있어도 시스템이 깨워준다.**
 * 통화가 끝나면 녹음 파일이 완성되기까지 시차가 있고 그 시점이 녹음 앱마다 달라서,
 * 20초 · 2분 · 10분 뒤 세 번에 걸쳐 스캔을 예약한다.
 *
 * (2026-07-13 앱이 75분간 죽어 있어 통화 2건이 통째로 누락된 사례 대응)
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                wasOffHook = true
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (!wasOffHook) return          // 부재중·거절은 녹음이 없으므로 무시
                wasOffHook = false
                Timber.i("통화 종료 감지 — 녹음 파일 확인 예약 (20초 · 2분 · 10분)")
                CallSweepWorker.runAfter(context, 20, "call-end-20s")
                CallSweepWorker.runAfter(context, 120, "call-end-2m")
                CallSweepWorker.runAfter(context, 600, "call-end-10m")
            }
        }
    }

    companion object {
        @Volatile private var wasOffHook = false
    }
}
