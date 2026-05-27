package app.jongpal.jjongpal.capture.notification

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.data.local.EventDao
import app.jongpal.jjongpal.data.repository.EventRepository
import app.jongpal.jjongpal.intelligence.FilterDecision
import app.jongpal.jjongpal.intelligence.GemmaClassifier
import app.jongpal.jjongpal.intelligence.NotificationFilter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class NotificationCaptureService : NotificationListenerService() {

    @Inject lateinit var eventRepository: EventRepository
    @Inject lateinit var eventDao: EventDao
    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var notificationFilter: NotificationFilter
    @Inject lateinit var gemmaClassifier: GemmaClassifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // 같은 출처·본문 알림이 이 시간 안 다시 들어오면 무시 (음악·게임 등 갱신 반복 차단)
        private const val DEDUP_WINDOW_MS = 60_000L  // 60 초
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.isOngoing) return
        if (sbn.packageName == applicationContext.packageName) return
        if (!tokenManager.hasValidSession()) return

        val extras: Bundle = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text  = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val content = (bigText ?: text)?.take(2000)
        val sender = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()

        if (title.isNullOrBlank() && content.isNullOrBlank()) return

        val pkg = sbn.packageName
        val ts = sbn.postTime

        // 결정적 ID — 같은 알림 (같은 sbn.key + postTime) 두 번 들어와도 디비 중복 방지
        // sbn.key 형식: "<userId>|<pkg>|<id>|<tag>|<uid>" — Postgres / Room 양쪽 호환 위해 안전한 문자만 유지
        val safeKey = (sbn.key ?: "$pkg-${sbn.id}").replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)
        val id = "n-$safeKey-$ts"

        scope.launch {
            try {
                // 0단계: 시간 창 안 같은 본문 dedup
                val since = ts - DEDUP_WINDOW_MS
                val recentDup = eventDao.countRecentDuplicate(pkg, title, content, since)
                if (recentDup > 0) {
                    Timber.d("notif dedup window hit: pkg=%s title=%s", pkg, title)
                    return@launch
                }

                // 1단계: 휴리스틱 필터
                val heuristic = notificationFilter.classify(pkg, title, content)

                // 2단계: Gemma 분류기 (활성화 시 — 현재 OFF 라 휴리스틱 결과 그대로)
                val finalResult =
                    if (heuristic.decision != FilterDecision.PENDING) heuristic
                    else if (GemmaClassifier.ENABLED) gemmaClassifier.classify(pkg, title, content)
                    else heuristic   // Gemma OFF + 휴리스틱 PENDING → 그대로 (PENDING 은 send 로 처리됨)

                // 결정 → DB 컬럼 값 매핑
                val phoneClass = when (finalResult.decision) {
                    FilterDecision.SEND, FilterDecision.PENDING -> "send"
                    FilterDecision.SKIP_NOISE -> "skip_noise"
                    FilterDecision.SKIP_SENSITIVE -> "skip_sensitive"
                }

                eventRepository.captureNotification(
                    eventId = id,
                    sourcePackage = pkg,
                    title = title,
                    content = content,
                    sender = sender,
                    timestampMs = ts,
                    phoneClassification = phoneClass,
                    phoneClassificationReason = finalResult.reason,
                )

                Timber.d(
                    "notif captured pkg=%s decision=%s reason=%s",
                    pkg, phoneClass, finalResult.reason,
                )
            } catch (e: Exception) {
                Timber.e(e, "captureNotification error")
            }
        }
    }
}
