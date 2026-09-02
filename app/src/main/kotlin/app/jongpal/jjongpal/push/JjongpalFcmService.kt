package app.jongpal.jjongpal.push

import android.app.PendingIntent
import android.content.Intent
import app.jongpal.jjongpal.MainActivity
import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.data.remote.DevicePatch
import app.jongpal.jjongpal.data.remote.PcApi
import app.jongpal.jjongpal.sync.SyncScheduler
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class JjongpalFcmService : FirebaseMessagingService() {

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var pcApi: PcApi
    @Inject lateinit var syncScheduler: SyncScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("new FCM token")
        val deviceId = tokenManager.deviceId ?: return
        if (!tokenManager.hasValidSession()) return
        scope.launch {
            try {
                pcApi.patchDevice("eq.$deviceId", DevicePatch(fcm_token = token))
                Timber.i("FCM token uploaded for device %s", deviceId)
            } catch (e: Exception) {
                Timber.e(e, "FCM token upload error")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val type = message.data["type"]
        Timber.d("FCM message received type=%s", type)

        // 시스템 알림(파이프라인 문제 등) — 폰에 바로 띄운다. 로그인 여부와 무관.
        if (type == "alert") {
            showAlert(
                title = message.data["title"] ?: "쫑팔이삼촌",
                body = message.data["body"] ?: "",
                level = message.data["level"] ?: "warn",
            )
            return
        }

        if (!tokenManager.hasValidSession()) return

        // 정리(요약) 완료 푸시 — 요약 첫 줄을 폰이 PC 에서 가져와 알림 본문에 담고, 눌러서 그 통화 정리로 이동.
        if (type == "summary_ready") {
            val summaryId = message.data["summary_id"]
            scope.launch {
                val snippet = try {
                    fetchSummarySnippet(summaryId)
                } catch (e: Exception) {
                    Timber.w(e, "summary snippet fetch failed")
                    null
                }
                showSummaryReady(summaryId, snippet)
            }
        }
        // 일반 푸시는 트리거만. 본문은 폰이 PC 에서 다시 가져옴.
        syncScheduler.scheduleNow()
    }

    /** 알림을 누르면 앱(MainActivity)을 연다. summaryId 가 있으면 그 통화 정리로 이동시킨다. */
    private fun mainPendingIntent(summaryId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("nav", true)
            if (!summaryId.isNullOrBlank()) putExtra("summary_id", summaryId)
        }
        val reqCode = summaryId?.hashCode() ?: 0
        return PendingIntent.getActivity(
            this, reqCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** 요약 본문 첫 줄(마크다운 헤더·기호 제거)을 알림에 쓸 짧은 스니펫으로. 실패하면 null. */
    private suspend fun fetchSummarySnippet(summaryId: String?): String? {
        if (summaryId.isNullOrBlank()) return null
        val resp = pcApi.getSummaryById("eq.$summaryId")
        val text = resp.body()?.firstOrNull()?.summary ?: return null
        val line = text.lineSequence()
            .map { it.trim().trimStart('#', ' ', '*', '-', '·', '>').trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        return if (line.length > 80) line.take(78) + "…" else line
    }

    private fun showSummaryReady(summaryId: String?, snippet: String?) {
        val ch = "jjongpal_summary_ready"
        val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(ch, "쫑팔 통화 정리", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "통화 정리가 끝나면 알려줘요" }
                )
            }
        }
        val body = if (!snippet.isNullOrBlank()) snippet else "눌러서 확인해요"
        val n = androidx.core.app.NotificationCompat.Builder(this, ch)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("통화 정리 완료")
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(mainPendingIntent(summaryId))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            val id = summaryId?.hashCode() ?: 9200
            androidx.core.app.NotificationManagerCompat.from(this).notify(id, n)
        } catch (_: SecurityException) { }
    }

    private fun showAlert(title: String, body: String, level: String) {
        val ch = "jjongpal_system_alert"
        val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(ch, "쫑팔 시스템 알림", android.app.NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "받아쓰기·요약이 멈추거나 문제가 생기면 알려줘요" }
                )
            }
        }
        val icon = if (level == "ok") android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error
        val n = androidx.core.app.NotificationCompat.Builder(this, ch)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(mainPendingIntent(null))
            .setAutoCancel(true)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(9100 + (title.hashCode() and 0xFF), n)
        } catch (_: SecurityException) { }
    }
}
