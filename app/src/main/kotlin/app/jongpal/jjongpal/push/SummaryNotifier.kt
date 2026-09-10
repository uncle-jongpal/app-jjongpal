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
import app.jongpal.jjongpal.data.remote.SummaryDto
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 서버(PC)의 새 통화 요약을 주기적으로 확인해 폰에 로컬 알림을 띄운다.
 * FCM(파이어베이스 푸시)을 걷어내고 그 자리를 대신하는 로컬 폴링 방식.
 *
 * - 서버 푸시 없음. 폰이 `GET /rest/summaries?order=created_at.desc` 를 직접 조회한다.
 * - "마지막으로 본 요약" 시각(TokenManager.lastSeenSummaryAt)보다 새로 만들어진 요약만 알림.
 * - 첫 실행/로그인 직후(마커=0)엔 알림 없이 현재 최신값으로 마커만 초기화 → 과거 요약 밀림(스팸) 방지.
 * - 알림 탭 → MainActivity 로 이동하며 해당 요약으로 딥링크(기존 FCM 알림과 동일 동작).
 */
@Singleton
class SummaryNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pcApi: PcApi,
    private val tokenManager: TokenManager,
) {

    /** 새 요약이 있는지 확인하고, 있으면 각각 로컬 알림을 띄운다. 네트워크/권한 실패는 조용히 넘긴다. */
    suspend fun checkAndNotify() {
        if (!tokenManager.hasValidSession()) return

        val resp = try {
            pcApi.listSummaries(userIdEq = currentUserFilter(), limit = POLL_LIMIT)
        } catch (e: Exception) {
            Timber.w(e, "summary poll fetch failed")
            return
        }
        if (!resp.isSuccessful) {
            Timber.w("summary poll failed ${resp.code()}")
            return
        }

        val items = resp.body().orEmpty()
            .mapNotNull { s -> parseIso(s.created_at)?.let { ts -> ts to s } }
        if (items.isEmpty()) return

        val newest = items.maxOf { it.first }
        val lastSeen = tokenManager.lastSeenSummaryAt

        // 첫 실행/로그인 직후 — 과거 요약으로 도배하지 않도록 마커만 최신으로 맞추고 종료.
        if (lastSeen <= 0L) {
            tokenManager.lastSeenSummaryAt = newest
            Timber.i("summary poll: first run, marker initialized (no backlog notifications)")
            return
        }

        // 마지막으로 본 시각 이후로 새로 생긴 요약만, 오래된 순으로 알림.
        val fresh = items.filter { it.first > lastSeen }.sortedBy { it.first }
        for ((_, s) in fresh) {
            notifySummary(s.id, firstLine(s.summary))
        }
        if (newest > lastSeen) tokenManager.lastSeenSummaryAt = newest
        if (fresh.isNotEmpty()) Timber.i("summary poll: %d new summaries notified", fresh.size)
    }

    /** 요약 본문 첫 줄(마크다운 헤더·기호 제거)을 알림 본문용 짧은 스니펫으로. 비면 null. */
    private fun firstLine(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val line = text.lineSequence()
            .map { it.trim().trimStart('#', ' ', '*', '-', '·', '>').trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        return if (line.length > 80) line.take(78) + "…" else line
    }

    private fun notifySummary(summaryId: String?, snippet: String?) {
        val ch = CHANNEL_ID
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(ch, "쫑팔 통화 정리", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "통화 정리가 끝나면 알려줘요" }
                )
            }
        }
        val body = if (!snippet.isNullOrBlank()) snippet else "눌러서 확인해요"
        val n = NotificationCompat.Builder(context, ch)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("새 통화 요약")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(mainPendingIntent(summaryId))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            val id = summaryId?.hashCode() ?: 9200
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS 미허용 — 조용히 넘김.
        }
    }

    /** 알림을 누르면 앱(MainActivity)을 연다. summaryId 가 있으면 그 통화 정리로 이동시킨다. */
    private fun mainPendingIntent(summaryId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("nav", true)
            if (!summaryId.isNullOrBlank()) putExtra("summary_id", summaryId)
        }
        val reqCode = summaryId?.hashCode() ?: 0
        return PendingIntent.getActivity(
            context, reqCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // user_id 필터 — SummaryRepository 와 동일 규칙.
    //   어드민 + "모든 사용자 보기" 켬 → null (전체), 그 외 → "eq.<본인 id>"
    private fun currentUserFilter(): String? {
        val isAdmin = tokenManager.userRole == "admin"
        if (isAdmin && tokenManager.showAllUsersForAdmin) return null
        val uid = tokenManager.userId
        return if (uid > 0) "eq.$uid" else null
    }

    private fun parseIso(s: String): Long? = try {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try { java.time.Instant.parse(s).toEpochMilli() } catch (e2: Exception) { null }
    }

    companion object {
        const val CHANNEL_ID = "jjongpal_summary_ready"
        private const val POLL_LIMIT = 20
    }
}
