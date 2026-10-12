package app.jongpal.jjongpal.capture.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 통화 녹음 파일의 상대 전화번호 찾기.
 *
 * 예전엔 감시 서비스 콜백에서만 번호를 찾았고, 주기 스캔(CallSweepWorker)이 먼저 등록하면
 * (IGNORE 삽입이라) 번호 없는 채로 굳었다. 이제 모든 등록 경로(EventRepository.captureCallFile)와
 * 업로드 직전(CallUploadWorker)에서 이걸 부른다.
 *
 * 순서: 파일 이름의 숫자 번호(삼성 "통화 010..._날짜_시각") → 통화기록(끝 시각 기준 짝짓기).
 * 파일 이름 번호를 먼저 보는 이유: 녹음기가 직접 적은 상대 번호라 시각 짝짓기보다 확실하다.
 * 음성 메모로 보이는 파일엔 통화기록 번호를 붙이지 않는다(엉뚱한 번호 방지).
 */
@Singleton
class CallNumberResolver @Inject constructor(@ApplicationContext private val context: Context) {

    fun resolve(filePath: String, recordingEndMs: Long): String? {
        val name = File(filePath).name
        CallNumberMatcher.parseNumberFromFileName(name)?.let { return it }
        if (CallNumberMatcher.looksLikeVoiceMemo(filePath)) return null
        return fromCallLog(recordingEndMs)
    }

    // 통화기록에서 녹음 끝 시각 근처에 끝난 통화를 찾음. READ_CALL_LOG 미허용이면 조용히 null.
    private fun fromCallLog(recordingEndMs: Long): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION)
            // 시작 시각이 [녹음 끝 - 최대 통화 길이, 녹음 끝 + 오차] 안인 것만 — 긴 통화(수 시간)도 잡히게 넉넉히.
            val selection = "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?"
            val args = arrayOf(
                (recordingEndMs - MAX_CALL_MS).toString(),
                (recordingEndMs + CallNumberMatcher.END_TOLERANCE_MS).toString(),
            )
            val entries = ArrayList<CallNumberMatcher.CallLogEntry>()
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, args,
                CallLog.Calls.DATE + " DESC",
            )?.use { c ->
                val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                while (c.moveToNext() && entries.size < MAX_ROWS) {
                    entries += CallNumberMatcher.CallLogEntry(
                        number = if (numIdx >= 0) c.getString(numIdx) else null,
                        dateMs = if (dateIdx >= 0) c.getLong(dateIdx) else 0L,
                        durationSec = if (durIdx >= 0) c.getLong(durIdx) else 0L,
                    )
                }
            }
            CallNumberMatcher.bestMatch(entries, recordingEndMs)
        } catch (e: Exception) {
            Timber.w(e, "call log lookup failed")
            null
        }
    }

    companion object {
        // 통화기록 검색 범위 — 녹음 끝에서 이만큼 이전에 시작한 통화까지 본다(최장 통화 길이 가정).
        private const val MAX_CALL_MS = 6L * 60 * 60 * 1000
        private const val MAX_ROWS = 100
    }
}
