package app.jongpal.jjongpal.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    val todos: StateFlow<List<TodoEntity>> = todoRepository.activeStream()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val summaries: StateFlow<List<SummaryEntity>> = summaryRepository.recent()
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
}
