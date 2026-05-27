package app.jongpal.jjongpal.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.jongpal.jjongpal.ui.permission.PermissionScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onLogout: () -> Unit,
    userName: String?,
    userId: Int,
    vm: MainViewModel = hiltViewModel(),
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("할 일", "통화 정리", "약속", "원본 알림", "권한", "설정")

    val todos by vm.todos.collectAsState()
    val summaries by vm.summaries.collectAsState()
    val appointments by vm.appointments.collectAsState()
    val rawEvents by vm.rawEvents.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("쫑팔이삼촌") },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Text("↻") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { idx, label ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        icon = { Text(label.first().toString()) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { inner ->
        Box(modifier = Modifier.padding(inner).fillMaxSize()) {
            when (selectedTab) {
                0 -> TodoTab(
                    todos = todos,
                    userId = userId,
                    onAdd = { vm.addTodo(userId, it) },
                    onToggle = { id, done -> vm.toggleTodo(id, done) },
                )
                1 -> SummariesTab(summaries = summaries)
                2 -> AppointmentsTab(appointments = appointments)
                3 -> RawEventsTab(events = rawEvents)
                4 -> PermissionScreen()
                5 -> SettingsTab(userName = userName, onLogout = onLogout)
            }
        }
    }
}

@Composable
private fun TodoTab(
    todos: List<app.jongpal.jjongpal.data.local.TodoEntity>,
    userId: Int,
    onAdd: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("새 할 일") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onAdd(input); input = "" }, enabled = input.isNotBlank()) { Text("추가") }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(todos, key = { it.id }) { todo ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = todo.status == "done",
                            onCheckedChange = { onToggle(todo.id, it) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(todo.content, style = MaterialTheme.typography.bodyLarge)
                            val src = when (todo.source) {
                                "manual" -> "직접 추가"
                                "call" -> "통화에서 추출"
                                "notification" -> "알림에서 추출"
                                else -> todo.source
                            }
                            Text(src ?: "", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummariesTab(summaries: List<app.jongpal.jjongpal.data.local.SummaryEntity>) {
    if (summaries.isEmpty()) {
        EmptyMsg("아직 정리된 통화가 없음. 통화 끝나면 PC 가 처리한 후 여기에 떠요.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(summaries, key = { it.id }) { s ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(s.summary ?: "(요약 없음)", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun AppointmentsTab(appointments: List<app.jongpal.jjongpal.data.local.AppointmentEntity>) {
    if (appointments.isEmpty()) {
        EmptyMsg("아직 약속 없음. 통화·알림에서 약속이 추출되면 여기에 떠요.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(appointments, key = { it.id }) { a ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(a.title, style = MaterialTheme.typography.bodyLarge)
                    Text(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREAN).format(java.util.Date(a.startAt)), style = MaterialTheme.typography.bodySmall)
                    if (!a.location.isNullOrBlank()) Text("장소: " + a.location, style = MaterialTheme.typography.bodySmall)
                    if (!a.withPerson.isNullOrBlank()) Text("함께: " + a.withPerson, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RawEventsTab(events: List<app.jongpal.jjongpal.data.local.EventEntity>) {
    if (events.isEmpty()) {
        EmptyMsg("아직 캡처된 알림 / 통화 없음. 알림 도착하면 여기에 떠요.")
        return
    }
    val dateFmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.KOREAN) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(events, key = { it.id }) { e ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val typeLabel = when (e.type) { "notification" -> "알림"; "call" -> "통화"; else -> e.type }
                    val syncLabel = when (e.syncStatus) {
                        "PENDING" -> "대기"
                        "SYNCING" -> "전송 중"
                        "SYNCED" -> "동기화 완료"
                        "FAILED" -> "실패"
                        else -> e.syncStatus
                    }
                    Text("[$typeLabel] " + (e.title ?: "(제목 없음)"), style = MaterialTheme.typography.bodyLarge)
                    if (!e.content.isNullOrBlank()) {
                        Text(e.content.take(160), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!e.sourcePackage.isNullOrBlank()) {
                        Text("출처: " + e.sourcePackage, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("시각: " + dateFmt.format(java.util.Date(e.timestamp)) + " · 동기화: " + syncLabel +
                        (if (e.retryCount > 0) " (재시도 ${e.retryCount})" else ""),
                        style = MaterialTheme.typography.labelSmall)
                    if (!e.lastError.isNullOrBlank()) {
                        Text("에러: " + e.lastError.take(120), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(userName: String?, onLogout: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("설정", style = MaterialTheme.typography.titleLarge)
        Text("사용자: " + (userName ?: "(없음)"))
        Button(onClick = onLogout) { Text("로그아웃") }
    }
}

@Composable
private fun EmptyMsg(msg: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(msg, style = MaterialTheme.typography.bodyMedium)
    }
}
