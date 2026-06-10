package app.jongpal.jjongpal.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.data.local.AppointmentEntity
import app.jongpal.jjongpal.data.local.EventDao
import app.jongpal.jjongpal.data.local.EventEntity
import app.jongpal.jjongpal.data.local.SummaryEntity
import app.jongpal.jjongpal.data.local.TodoEntity
import app.jongpal.jjongpal.data.repository.AppointmentRepository
import app.jongpal.jjongpal.data.repository.SummaryRepository
import app.jongpal.jjongpal.data.repository.TodoRepository
import app.jongpal.jjongpal.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val todoRepository: TodoRepository,
    private val summaryRepository: SummaryRepository,
    private val appointmentRepository: AppointmentRepository,
    private val eventDao: EventDao,
    private val syncScheduler: SyncScheduler,
    private val tokenManager: TokenManager,
) : ViewModel() {

    // 사용자 역할 (admin / user) — 설정 토글 노출 조건
    val userRole: String? get() = tokenManager.userRole
    val isAdmin: Boolean get() = userRole == "admin"

    // 어드민의 "모든 사용자 보기" 토글 상태 — UI 가 관찰
    private val _showAllUsers = MutableStateFlow(tokenManager.showAllUsersForAdmin)
    val showAllUsers: StateFlow<Boolean> = _showAllUsers.asStateFlow()

    fun setShowAllUsers(show: Boolean) {
        tokenManager.showAllUsersForAdmin = show
        _showAllUsers.value = show
        // 토글 변경 즉시 서버에서 다시 받아옴
        syncScheduler.scheduleNow()
    }

    val currentUserId: Int get() = tokenManager.userId

    val todos: StateFlow<List<TodoEntity>> = todoRepository.activeStream()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 삼촌이 찾아 확인 대기 중인 제안 (status = 'suggested')
    val suggestions: StateFlow<List<TodoEntity>> = todoRepository.suggestionsStream()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 통화 정리 — showAllUsers 토글에 반응해서 흐름 전환:
    //   모든 사용자 보기 켬  → 모든 행
    //   꺼짐 (기본) 또는 일반 사용자 → 본인 행만
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val summaries: StateFlow<List<SummaryEntity>> = _showAllUsers
        .flatMapLatest { showAll ->
            if (showAll && isAdmin) {
                summaryRepository.recent()
            } else {
                summaryRepository.recentByUser(tokenManager.userId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val appointments: StateFlow<List<AppointmentEntity>> = appointmentRepository.upcoming()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rawEvents: StateFlow<List<EventEntity>> = eventDao.recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refresh() {
        syncScheduler.scheduleNow()
    }

    fun addTodo(userId: Int, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            todoRepository.addManual(userId, content.trim())
            syncScheduler.scheduleNow()
        }
    }

    fun toggleTodo(id: String, done: Boolean) {
        viewModelScope.launch {
            todoRepository.setDone(id, done)
            syncScheduler.scheduleNow()
        }
    }

    // 삼촌 제안 → 할 일로 확정
    fun acceptSuggestion(id: String) {
        viewModelScope.launch {
            todoRepository.accept(id)
            syncScheduler.scheduleNow()
        }
    }

    // 삼촌 제안 → 약속으로 (로컬 약속 생성 후 제안은 넘김)
    fun suggestionToAppointment(t: TodoEntity) {
        viewModelScope.launch {
            appointmentRepository.createLocal(
                userId = t.userId,
                title = t.content,
                startAt = t.dueAt ?: System.currentTimeMillis(),
                sourceEventId = t.sourceEventId,
                sourceExcerpt = t.sourceExcerpt,
                withPerson = t.relatedPerson,
            )
            todoRepository.dismiss(t.id)
            syncScheduler.scheduleNow()
        }
    }

    // 삼촌 제안 → 넘기기
    fun dismissSuggestion(id: String) {
        viewModelScope.launch {
            todoRepository.dismiss(id)
            syncScheduler.scheduleNow()
        }
    }

    fun confirmAppointment(id: String) {
        viewModelScope.launch {
            appointmentRepository.confirm(id)
            syncScheduler.scheduleNow()
        }
    }
}
