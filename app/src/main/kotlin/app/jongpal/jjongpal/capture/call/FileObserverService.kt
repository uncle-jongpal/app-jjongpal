package app.jongpal.jjongpal.capture.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import androidx.core.content.ContextCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors
import java.security.MessageDigest
import javax.inject.Inject

@AndroidEntryPoint
class FileObserverService : Service() {

    @Inject lateinit var observer: CallRecordingObserver
    @Inject lateinit var eventRepository: EventRepository
    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var syncScheduler: SyncScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 통화 상태 감시 — OFFHOOK(통화중) → IDLE(종료) 전이 시점에 녹음 완성본 스캔 트리거.
    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null
    private val telephonyExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var wasOffHook = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        registerCallStateDetector()
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
                    phoneNumber = readLatestCallNumber(ts),
                )
                syncScheduler.scheduleNow()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterCallStateDetector()
        observer.stop()
        super.onDestroy()
    }

    // 통화 종료 감지기 등록. API 31+ 는 TelephonyCallback, 그 이하는 PhoneStateListener.
    // 종료 전이(OFFHOOK→IDLE)에서만 onCallEnded() 호출 — 단순 호출/걸려옴(IDLE 유지)엔 반응 안 함.
    private fun registerCallStateDetector() {
        // onStartCommand 는 여러 번 불릴 수 있음(START_STICKY 재전달·부팅/스케줄러 재시작) — 한 번만 등록.
        // 중복 등록하면 콜백이 쌓여 onCallEnded 가 여러 번 터지고 리스너가 샘.
        if (telephonyCallback != null || phoneStateListener != null) return
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        telephonyManager = tm
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleCallState(state)
                }
                tm.registerTelephonyCallback(telephonyExecutor, cb)
                telephonyCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleCallState(state)
                }
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = listener
            }
            Timber.i("call-state detector registered")
        } catch (e: SecurityException) {
            // READ_PHONE_STATE 미허용 — 종료 감지 없이도 MediaStore 옵저버로 동작은 함(타이밍만 덜 정확).
            Timber.w(e, "call-state detector needs READ_PHONE_STATE; falling back to mediastore observer only")
        } catch (e: Exception) {
            Timber.e(e, "register call-state detector failed")
        }
    }

    private fun unregisterCallStateDetector() {
        try {
            val tm = telephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let { tm?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                phoneStateListener?.let { tm?.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Exception) {
        }
        telephonyCallback = null
        phoneStateListener = null
        telephonyManager = null
        try { telephonyExecutor.shutdown() } catch (_: Exception) {}
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> wasOffHook = true
            TelephonyManager.CALL_STATE_IDLE -> {
                if (wasOffHook) {
                    wasOffHook = false
                    onCallEnded()
                }
            }
        }
    }

    // 통화 종료 직후 — 녹음기가 파일을 닫고 최종화(moov 기록)할 시간을 잠깐 준 뒤 스캔.
    // 스캔이 새 완성본을 잡으면 업로드 작업이 예약됨(업로드 측에서 moov 재확인 후 전송).
    private fun onCallEnded() {
        scope.launch {
            Timber.i("call ended (OFFHOOK->IDLE), scheduling post-call scan")
            delay(CALL_END_SETTLE_MS)
            try {
                observer.scanNow(reason = "call-ended")
            } catch (e: Exception) {
                Timber.e(e, "post-call scan failed")
            }
            syncScheduler.scheduleNow()
        }
    }

    // 통화기록(CallLog)에서 녹음 시각에 가장 가까운 통화의 상대 번호를 읽음.
    // READ_CALL_LOG 미허용이면 조용히 null (기존 동작 유지). 10분 넘게 떨어진 건 신뢰 안 함.
    private fun readLatestCallNumber(aroundMs: Long): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE)
            contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, null, null,
                CallLog.Calls.DATE + " DESC",
            )?.use { c ->
                val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                var best: String? = null
                var bestDiff = Long.MAX_VALUE
                var scanned = 0
                while (c.moveToNext() && scanned < 20) {
                    scanned++
                    val num = if (numIdx >= 0) c.getString(numIdx) else null
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    val diff = kotlin.math.abs(date - aroundMs)
                    if (!num.isNullOrBlank() && diff < bestDiff) {
                        bestDiff = diff
                        best = num
                    }
                }
                if (bestDiff <= 10 * 60_000L) best?.filter { it.isDigit() || it == '+' } else null
            }
        } catch (e: Exception) {
            Timber.w(e, "readLatestCallNumber failed")
            null
        }
    }

    // 결정적 ID 로직은 CallId 로 통합(CallSweepWorker 와 동일 보장, 회귀 테스트로 고정)
    private fun deterministicId(filePath: String): String = CallId.deterministic(filePath)

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
        // 통화 종료 후 녹음기 최종화(파일 닫기·moov 기록) 대기 시간. 너무 짧으면 아직 안 닫힌 토막을 봄.
        private const val CALL_END_SETTLE_MS = 8_000L

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
