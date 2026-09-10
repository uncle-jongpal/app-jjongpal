package app.jongpal.jjongpal.auth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 세션 무효화 신호.
 * 토큰 재발급이 거부돼(리프레시 401) 세션이 더는 못 쓰게 되면 인터셉터가 이 신호를 쏘고,
 * 화면 쪽(AuthViewModel)이 받아 로그인 화면으로 되돌린다.
 */
@Singleton
class SessionManager @Inject constructor() {

    // extraBufferCapacity=1 → 구독자가 아직 없어도 tryEmit 이 유실되지 않게.
    private val _sessionInvalidated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionInvalidated: SharedFlow<Unit> = _sessionInvalidated.asSharedFlow()

    fun notifySessionInvalidated() {
        _sessionInvalidated.tryEmit(Unit)
    }
}
