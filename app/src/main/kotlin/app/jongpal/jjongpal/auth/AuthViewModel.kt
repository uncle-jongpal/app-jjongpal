package app.jongpal.jjongpal.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jongpal.jjongpal.util.ErrorContext
import app.jongpal.jjongpal.util.ErrorMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class AuthUiState(
    val loggedIn: Boolean = false,
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val userName: String? = null,
    val userId: Int? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repo: AuthRepository,
    private val tokenManager: TokenManager,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AuthUiState(
            loggedIn = tokenManager.hasValidSession(),
            userName = tokenManager.userName,
            userId = if (tokenManager.userId > 0) tokenManager.userId else null,
        )
    )
    val state: StateFlow<AuthUiState> = _state

    init {
        // 토큰 재발급 실패로 세션이 무효화되면 로그인 화면으로 되돌리고 안내 문구 표시.
        viewModelScope.launch {
            sessionManager.sessionInvalidated.collect {
                tokenManager.clear()
                _state.value = AuthUiState(loggedIn = false, errorMessage = "다시 로그인해주세요")
            }
        }
    }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _state.value = _state.value.copy(errorMessage = "이메일과 비밀번호를 입력하세요")
            return
        }
        _state.value = _state.value.copy(loading = true, errorMessage = null)
        viewModelScope.launch {
            val r = repo.login(email, password)
            r.onSuccess { user ->
                _state.value = AuthUiState(loggedIn = true, userName = user.name, userId = user.id)
            }.onFailure { e ->
                Timber.w(e, "login error")
                val msg = ErrorMessages.friendly(e, ErrorContext.LOGIN)
                _state.value = _state.value.copy(loading = false, errorMessage = msg)
            }
        }
    }

    fun logout() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            repo.logout()
            _state.value = AuthUiState(loggedIn = false)
        }
    }
}
