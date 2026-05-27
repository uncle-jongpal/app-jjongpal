package app.jongpal.jjongpal.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
) : ViewModel() {

    private val _state = MutableStateFlow(
        AuthUiState(
            loggedIn = tokenManager.hasValidSession(),
            userName = tokenManager.userName,
            userId = if (tokenManager.userId > 0) tokenManager.userId else null,
        )
    )
    val state: StateFlow<AuthUiState> = _state

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
                val msg = e.message ?: "알 수 없는 오류"
                _state.value = _state.value.copy(loading = false, errorMessage = "로그인 실패: $msg")
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
