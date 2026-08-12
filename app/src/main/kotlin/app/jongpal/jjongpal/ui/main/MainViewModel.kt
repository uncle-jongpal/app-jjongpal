package app.jongpal.jjongpal.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.data.local.AppointmentEntity
import app.jongpal.jjongpal.data.local.EventDao
import app.jongpal.jjongpal.data.local.EventEntity
import app.jongpal.jjongpal.data.local.SummaryEntity
import app.jongpal.jjongpal.data.local.TodoEntity
import app.jongpal.jjongpal.data.remote.FailedItemDto
import app.jongpal.jjongpal.data.repository.AppointmentRepository
import app.jongpal.jjongpal.data.repository.RetryRepository
import app.jongpal.jjongpal.data.repository.SummaryRepository
import app.jongpal.jjongpal.data.repository.TodoRepository
import app.jongpal.jjongpal.sync.SyncScheduler
import app.jongpal.jjongpal.sync.SyncStatus
import app.jongpal.jjongpal.sync.SyncStatusStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val todoRepository: TodoRepository,
    private val summaryRepository: SummaryRepository,
    private val appointmentRepository: AppointmentRepository,
    private val retryRepository: RetryRepository,
    private val eventDao: EventDao,
    private val syncScheduler: SyncScheduler,
    private val tokenManager: TokenManager,
    private val syncStatusStore: SyncStatusStore,
    private val pcApi: app.jongpal.jjongpal.data.remote.PcApi,
) : ViewModel() {

    // 마지막 동기화 결과 — 설정 화면 "PC 연결" 표시등이 관찰
    val syncStatus: StateFlow<SyncStatus> = syncStatusStore.state

    // 서버에서 처리 막힌 항목 (휘발성 — 화면 진입/새로고침 시 서버에서 직접 조회)
    private val _failed = MutableStateFlow<List<FailedItemDto>>(emptyList())
    val failed: StateFlow<List<FailedItemDto>> = _failed.asStateFlow()

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

    // 모든 목록은 showAllUsers 토글에 반응 — 끔(기본)/일반 사용자는 본인 계정 행만,
    // 어드민이 "모두 보기" 켜면 전체. 로컬에 다른 계정 데이터가 섞여 있어도 본인 것만 보이게.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val todos: StateFlow<List<TodoEntity>> = _showAllUsers
        .flatMapLatest { showAll ->
            if (showAll && isAdmin) todoRepository.activeStream()
            else todoRepository.activeStreamByUser(tokenManager.userId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 삼촌이 찾아 확인 대기 중인 제안 (status = 'suggested')
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val suggestions: StateFlow<List<TodoEntity>> = _showAllUsers
        .flatMapLatest { showAll ->
            if (showAll && isAdmin) todoRepository.suggestionsStream()
            else todoRepository.suggestionsStreamByUser(tokenManager.userId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 사용자가 보류해 둔 항목 (status = 'parked')
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val parked: StateFlow<List<TodoEntity>> = _showAllUsers
        .flatMapLatest { showAll ->
            if (showAll && isAdmin) todoRepository.parkedStream()
            else todoRepository.parkedStreamByUser(tokenManager.userId)
        }
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val appointments: StateFlow<List<AppointmentEntity>> = _showAllUsers
        .flatMapLatest { showAll ->
            if (showAll && isAdmin) appointmentRepository.upcoming()
            else appointmentRepository.upcomingByUser(tokenManager.userId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 약속 탭 전용 — 지난 약속까지 포함. 화면에서 날짜 묶기·필터링.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allAppointments: StateFlow<List<AppointmentEntity>> = _showAllUsers
        .flatMapLatest { showAll ->
            if (showAll && isAdmin) appointmentRepository.all()
            else appointmentRepository.allByUser(tokenManager.userId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rawEvents: StateFlow<List<EventEntity>> = eventDao.recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 통화 검색어 (번호 / 이름 / 내용)
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    fun setQuery(q: String) { _query.value = q }

    // 통화 event id → 상대 전화번호 (번호 검색·표시용). 서버에서 한 번 받아 캐시.
    private val _phoneByEvent = MutableStateFlow<Map<String, String>>(emptyMap())
    val phoneByEvent: StateFlow<Map<String, String>> = _phoneByEvent.asStateFlow()
    fun phoneFor(eventId: String?): String? = eventId?.let { _phoneByEvent.value[it] }

    // 검색어로 거른 통화 정리 목록. 비면 전체. 번호(숫자만 비교) 또는 이름·내용 일치.
    val filteredSummaries: StateFlow<List<SummaryEntity>> =
        combine(summaries, _query, _phoneByEvent) { list, q, phones ->
            val kw = q.trim()
            if (kw.isEmpty()) return@combine list
            val digits = kw.filter { it.isDigit() }
            list.filter { s ->
                val phone = s.eventId?.let { phones[it] }
                val phoneHit = digits.length >= 2 && phone != null &&
                    phone.filter { c -> c.isDigit() }.contains(digits)
                val textHit = (s.summary?.contains(kw, ignoreCase = true) == true) ||
                    s.rawJson.contains(kw, ignoreCase = true) ||
                    (s.transcriptText?.contains(kw, ignoreCase = true) == true)   // 원문(받아쓰기)도 검색
                phoneHit || textHit
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        ensureFcmTokenRegistered()
        refreshFailed()
        loadPhones()
    }

    fun refresh() {
        syncScheduler.scheduleNow()
        refreshFailed()
        loadPhones()
    }

    private fun loadPhones() {
        viewModelScope.launch {
            _phoneByEvent.value = summaryRepository.callPhones()
        }
    }

    fun refreshFailed() {
        viewModelScope.launch {
            _failed.value = retryRepository.listFailed().filter { it.stage != "event" }   // v2: 알림 실패 제외, 통화(받아쓰기·요약)만
        }
    }

    // 실패 항목 수동 재시도.
    // 탭 즉시 목록에서 빼서(팝) 반응성 확보. 서버가 실제로 못 되돌렸으면 새로고침으로 되살림.
    fun retryFailed(eventId: String) {
        val popped = _failed.value
        _failed.value = popped.filterNot { it.event_id == eventId }
        viewModelScope.launch {
            if (retryRepository.retry(eventId)) {
                syncScheduler.scheduleNow()
                refreshFailed()
            } else {
                _failed.value = popped
            }
        }
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

    // 여러 할 일 한 번에 완료 (날짜별 전체 완료)
    fun completeMany(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            todoRepository.setDoneMany(ids)
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

    // 삼촌 제안 → 보류 (나중에 보려고)
    fun parkSuggestion(id: String) {
        viewModelScope.launch {
            todoRepository.park(id)
            syncScheduler.scheduleNow()
        }
    }

    // 보류함 → 다시 검토 목록으로
    fun unparkSuggestion(id: String) {
        viewModelScope.launch {
            todoRepository.unpark(id)
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

    fun deleteAppointment(id: String) {
        viewModelScope.launch {
            appointmentRepository.delete(id)
        }
    }

    // 통화 정리 보관함 핀 토글
    fun setSummaryPinned(id: String, pinned: Boolean) {
        viewModelScope.launch {
            summaryRepository.setPinned(id, pinned)
        }
    }

    /** 앱 시작 시 FCM 토큰을 서버 devices 에 올린다. 재설치 시 onNewToken 이 안 떠도 알림이 오게. */
    private fun ensureFcmTokenRegistered() {
        val deviceId = tokenManager.deviceId ?: return
        if (!tokenManager.hasValidSession()) return
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                viewModelScope.launch {
                    try {
                        pcApi.patchDevice("eq.$deviceId",
                            app.jongpal.jjongpal.data.remote.DevicePatch(fcm_token = token))
                        timber.log.Timber.i("FCM 토큰 등록 완료")
                    } catch (e: Exception) {
                        timber.log.Timber.w(e, "FCM 토큰 등록 실패")
                    }
                }
            }
    }
}
