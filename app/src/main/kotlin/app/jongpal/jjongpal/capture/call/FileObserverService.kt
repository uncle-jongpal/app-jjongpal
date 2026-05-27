package app.jongpal.jjongpal.capture.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.jongpal.jjongpal.MainActivity
import app.jongpal.jjongpal.R
import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.data.repository.EventRepository
import app.jongpal.jjongpal.sync.SyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject

@AndroidEntryPoint
class FileObserverService : Service() {

    @Inject lateinit var observer: CallRecordingObserver
    @Inject lateinit var eventRepository: EventRepository
    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var syncScheduler: SyncScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        observer.start { filePath, ts ->
            if (!tokenManager.hasValidSession()) {
                Timber.d("skipping call file (no session): %s", filePath)
                return@start
            }
            scope.launch {
                // 파일 경로 기반 결정적 식별자 — 같은 파일 재스캔 시 데이터베이스의 IGNORE 충돌 처리로 자동 중복 방지
                val id = deterministicId(filePath)
                eventRepository.captureCallFile(
                    eventId = id,
                    filePath = filePath,
                    durationSec = null,
                    timestampMs = ts,
                )
                syncScheduler.scheduleNow()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observer.stop()
        super.onDestroy()
    }

    private fun deterministicId(filePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("call:" + filePath).toByteArray(Charsets.UTF_8))
        // SHA-256 의 앞 16바이트 = 32 헥스 — 충돌 가능성 무시 가능 수준
        val hex = StringBuilder(32)
        for (i in 0 until 16) {
            val b = digest[i].toInt() and 0xFF
            if (b < 0x10) hex.append('0')
            hex.append(b.toString(16))
        }
        return "call-" + hex.toString()
    }

    private fun startForegroundCompat() {
        val channelId = "jjongpal_capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "캡처 서비스", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("쫑팔이삼촌 동작 중")
            .setContentText("통화 / 알림 자동 캡처")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // specialUse — 안드로이드 14+ 의 dataSync 6시간/24시간 제한 회피.
            // 통화 녹음 파일 감시는 본질적으로 사용자 명시 작업, 지속 실행 필요.
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val NOTIF_ID = 7001

        fun start(context: Context) {
            val intent = Intent(context, FileObserverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FileObserverService::class.java))
        }
    }
}
