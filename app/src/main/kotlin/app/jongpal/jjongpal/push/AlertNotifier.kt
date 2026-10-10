package app.jongpal.jjongpal.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.jongpal.jjongpal.MainActivity
import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.data.remote.PcApi
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 서버 장애 알림(받아쓰기 밀림·요약 실패 등)을 폰 로컬 알림으로 띄운다. (2026-10-10, 0.5.7)
 *
 * - 서버 감시(watchdog)가 `app_alerts` 알림함에 쌓은 것을 요약 폴링과 같은 주기로 조회한다.
 *   (0.5.6 에서 FCM 을 걷어낸 뒤 감시 알림이 폰에 닿지 않던 문제 해결)
 * - 어드민만 볼 수 있다(RLS). 일반 사용자는 빈 목록이 와서 아무 일도 안 일어난다.
 * - 첫 실행/로그인 직후(마커=-1)엔 알림 없이 현재 최신 id 로 마커만 맞춘다 → 과거 알림 도배 방지.
 */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pcApi: PcApi,
    private val tokenManager: TokenManager,
) {

    suspend fun checkAndNotify() {
        if (!tokenManager.hasValidSession()) return
        if (tokenManager.userRole != "admin") return

        val lastSeen = tokenManager.lastSeenAlertId
        val resp = try {
            if (lastSeen < 0L) {
                // 첫 실행: 가장 최신 1건만 받아 마커로 삼는다.
                pcApi.listAppAlerts(idGt = null, order = "id.desc", limit = 1)
            } else {
                pcApi.listAppAlerts(idGt = "gt.$lastSeen", order = "id.asc", limit = POLL_LIMIT)
            }
        } catch (e: Exception) {
            Timber.w(e, "alert poll fetch failed")
            return
        }
        if (!resp.isSuccessful) {
            Timber.w("alert poll failed ${resp.code()}")
            return
        }
        val items = resp.body().orEmpty()

        if (lastSeen < 0L) {
            tokenManager.lastSeenAlertId = items.maxOfOrNull { it.id } ?: 0L
            Timber.i("alert poll: first run, marker initialized")
            return
        }
        if (items.isEmpty()) return

        for (a in items.sortedBy { it.id }) {
            notifyAlert(a.id, a.title, a.body, a.level)
        }
        tokenManager.lastSeenAlertId = items.maxOf { it.id }
        Timber.i("alert poll: %d new alerts notified", items.size)
    }

    private fun notifyAlert(id: Long, title: String, body: String?, level: String?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "쫑팔 서버 장애", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "받아쓰기·요약이 밀리거나 실패하면 알려줘요" }
                )
            }
        }
        val icon = when (level) {
            "crit" -> "🚨 "
            "warn" -> "⚠️ "
            "ok" -> "✅ "
            else -> ""
        }
        val text = if (!body.isNullOrBlank()) body else "앱에서 확인해 주세요"
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, (ALERT_ID_BASE + id).toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(icon + title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify((ALERT_ID_BASE + id).toInt(), n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS 미허용 — 조용히 넘김.
        }
    }

    companion object {
        const val CHANNEL_ID = "jjongpal_server_alert"
        private const val POLL_LIMIT = 20
        private const val ALERT_ID_BASE = 500_000L
    }
}
