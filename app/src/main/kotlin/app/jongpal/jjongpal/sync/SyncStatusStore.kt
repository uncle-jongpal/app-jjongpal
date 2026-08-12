package app.jongpal.jjongpal.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// 마지막 동기화 결과를 폰에 남겨 설정 화면 표시등이 실제 상태를 읽게 함.
// 표시등이 무조건 초록불로 박혀 있던 문제(실연결과 무관)를 고치기 위함.
@Singleton
class SyncStatusStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("jjongpal_sync", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SyncStatus> = _state.asStateFlow()

    private fun load() = SyncStatus(
        lastSuccessAt = prefs.getLong("last_success_at", 0L),
        lastAttemptAt = prefs.getLong("last_attempt_at", 0L),
        lastResultOk = prefs.getBoolean("last_result_ok", false),
    )

    fun record(ok: Boolean) {
        val now = System.currentTimeMillis()
        prefs.edit().apply {
            putLong("last_attempt_at", now)
            putBoolean("last_result_ok", ok)
            if (ok) putLong("last_success_at", now)
        }.apply()
        _state.value = load()
    }
}

data class SyncStatus(
    val lastSuccessAt: Long,
    val lastAttemptAt: Long,
    val lastResultOk: Boolean,
) {
    val everSynced: Boolean get() = lastSuccessAt > 0L

    // 최근 15분 안에 성공했고 마지막 시도가 실패가 아니면 "연결됨"으로 본다.
    fun isHealthy(now: Long = System.currentTimeMillis()): Boolean =
        lastResultOk && everSynced && (now - lastSuccessAt) < 15 * 60 * 1000L
}
