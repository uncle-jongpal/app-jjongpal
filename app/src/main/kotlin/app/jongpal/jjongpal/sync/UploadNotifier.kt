package app.jongpal.jjongpal.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.jongpal.jjongpal.R

/**
 * 통화 업로드 진행 상황 알림.
 *
 * 지금까지는 업로드가 조용히 진행되고 조용히 실패해서, 실패해도 아무도 몰랐다.
 * (2026-07-30 83MB 녹음이 6번 시간 초과로 실패한 뒤 그대로 묻힘)
 *
 * - 진행 중: 퍼센트 진행바
 * - 완료: 몇 건 올렸는지
 * - 실패: **원인을 그대로 보여준다** (시간 초과 / 서버 오류 / 파일 없음 …)
 */
class UploadNotifier(private val context: Context) {

    private val nm = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CH_PROGRESS) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CH_PROGRESS, "업로드 진행", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "통화 녹음을 서버로 올리는 중" }
                )
            }
            if (mgr.getNotificationChannel(CH_RESULT) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CH_RESULT, "업로드 결과", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "업로드 완료·실패 알림" }
                )
            }
        }
    }

    /** 업로드 중 — 퍼센트 진행바 */
    fun progress(name: String, index: Int, total: Int, percent: Int, sentMb: Double, totalMb: Double) {
        val title = if (total > 1) "통화 녹음 올리는 중 ($index/$total)" else "통화 녹음 올리는 중"
        val n = NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText("$name · ${"%.1f".format(sentMb)}MB / ${"%.1f".format(totalMb)}MB")
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        safeNotify(ID_PROGRESS, n)
    }

    /** 준비 단계(사본 만들기 등) — 퍼센트를 알 수 없을 때 */
    fun preparing(name: String) {
        val n = NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("통화 녹음 준비 중")
            .setContentText(name)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        safeNotify(ID_PROGRESS, n)
    }

    fun clearProgress() = nm.cancel(ID_PROGRESS)

    /** 업로드 완료 */
    fun done(okCount: Int, failedCount: Int, deferredCount: Int) {
        clearProgress()
        if (okCount == 0 && failedCount == 0) return
        val parts = mutableListOf<String>()
        if (okCount > 0) parts += "올림 ${okCount}건"
        if (failedCount > 0) parts += "실패 ${failedCount}건"
        if (deferredCount > 0) parts += "대기 ${deferredCount}건"
        val n = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(if (failedCount > 0) "업로드 일부 실패" else "업로드 완료")
            .setContentText(parts.joinToString(" · "))
            .setAutoCancel(true)
            .build()
        safeNotify(ID_RESULT, n)
    }

    /** 업로드 실패 — 원인을 사람이 읽을 수 있게 */
    fun failed(name: String, rawError: String?) {
        clearProgress()
        val reason = humanReason(rawError)
        val n = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("통화 업로드 실패")
            .setContentText("$name — $reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$name\n\n원인: $reason\n\n(자동으로 다시 시도해)"))
            .setAutoCancel(true)
            .build()
        safeNotify(ID_RESULT + name.hashCode().and(0xFF), n)
    }

    private fun safeNotify(id: Int, n: android.app.Notification) {
        try { nm.notify(id, n) } catch (_: SecurityException) { /* 알림 권한 없음 */ }
    }

    companion object {
        private const val CH_PROGRESS = "upload_progress"
        private const val CH_RESULT = "upload_result"
        private const val ID_PROGRESS = 4101
        private const val ID_RESULT = 4200

        /** 기계 오류 문구를 사람 말로 */
        fun humanReason(raw: String?): String {
            val e = (raw ?: "").lowercase()
            return when {
                e.contains("timeout") || e.contains("timed out") -> "시간 초과 (파일이 크거나 인터넷이 느림)"
                e.contains("unable to resolve host") || e.contains("unknownhost") -> "서버에 연결할 수 없음 (인터넷 확인)"
                e.contains("failed to connect") || e.contains("connect") -> "서버 연결 실패"
                e.contains("file missing") -> "녹음 파일이 없음 (폰에서 지워졌을 수 있음)"
                e.contains("no file_path") -> "녹음 파일 경로를 모름"
                e.contains("401") || e.contains("403") -> "로그인이 만료됨 (앱에서 다시 로그인 필요)"
                e.contains("413") -> "파일이 너무 커서 서버가 거부함"
                e.contains("upload 5") -> "서버 오류 (잠시 후 다시 시도)"
                e.contains("bytes but received") -> "업로드 중 파일이 바뀜 (녹음이 아직 끝나지 않았음)"
                e.isBlank() -> "알 수 없는 오류"
                else -> raw!!.take(60)
            }
        }
    }
}
