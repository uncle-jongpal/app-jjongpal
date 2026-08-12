package app.jongpal.jjongpal.ui.main

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.jongpal.jjongpal.data.local.AppointmentEntity
import app.jongpal.jjongpal.data.local.EventEntity
import app.jongpal.jjongpal.data.local.SummaryEntity
import app.jongpal.jjongpal.data.local.TodoEntity
import app.jongpal.jjongpal.data.remote.FailedItemDto
import app.jongpal.jjongpal.sync.SyncStatus
import app.jongpal.jjongpal.ui.components.GhostButton
import app.jongpal.jjongpal.ui.components.HLine
import app.jongpal.jjongpal.ui.components.JpCard
import app.jongpal.jjongpal.ui.components.PillAction
import app.jongpal.jjongpal.ui.components.PrimaryButton
import app.jongpal.jjongpal.ui.components.SectionHeader
import app.jongpal.jjongpal.ui.components.SrcTag
import app.jongpal.jjongpal.ui.components.Tick
import app.jongpal.jjongpal.ui.components.UncleAvatar
import app.jongpal.jjongpal.ui.permission.hasAllFilesAccess
import app.jongpal.jjongpal.ui.permission.hasCallLogPermission
import app.jongpal.jjongpal.ui.permission.hasMediaAudioPermission
import app.jongpal.jjongpal.ui.permission.hasPhoneStatePermission
import app.jongpal.jjongpal.ui.permission.isBatteryUnrestricted
import app.jongpal.jjongpal.ui.permission.isNotificationAccessGranted
import app.jongpal.jjongpal.ui.theme.JpText
import app.jongpal.jjongpal.ui.theme.JpTheme

private data class NavItem(val label: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onLogout: () -> Unit,
    userName: String?,
    userId: Int,
    openSummaryId: String? = null,
    navTick: Int = 0,
    vm: MainViewModel = hiltViewModel(),
) {
    val c = JpTheme.colors
    var tab by remember { mutableIntStateOf(2) }   // v2: 통화 화면으로 진입

    // 알림을 눌러 들어오면 통화 화면으로 전환
    var lastNavTab by remember { mutableIntStateOf(0) }
    LaunchedEffect(navTick) { if (navTick != lastNavTab) { lastNavTab = navTick; tab = 2 } }
    var showProfile by remember { mutableStateOf(false) }

    val todos by vm.todos.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val parked by vm.parked.collectAsState()
    val summaries by vm.summaries.collectAsState()
    val filteredSummaries by vm.filteredSummaries.collectAsState()
    val recordQuery by vm.query.collectAsState()
    val phoneByEvent by vm.phoneByEvent.collectAsState()
    val appointments by vm.appointments.collectAsState()
    val allAppointments by vm.allAppointments.collectAsState()
    val rawEvents by vm.rawEvents.collectAsState()
    val failed by vm.failed.collectAsState()
    val showAllUsers by vm.showAllUsers.collectAsState()
    val syncStatus by vm.syncStatus.collectAsState()
    val isAdmin = vm.isAdmin

    if (showProfile) {
        ProfileScreen(userName = userName, userId = userId, isAdmin = isAdmin, onBack = { showProfile = false }, onLogout = onLogout)
        return
    }

    // v2 = 통화 녹음 정리 전용. 할 일·오늘 브리핑은 화면에서 감춤(데이터·코드는 보존).
    val items = listOf(
        Triple("통화", Icons.Filled.Phone, 2),
        Triple("설정", Icons.Filled.Settings, 3),
    )

    Scaffold(
        containerColor = c.bg,
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface)
                    .border(width = 0.dp, color = c.line)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(top = 9.dp, bottom = 8.dp),
            ) {
                items.forEach { (label, icon, target) ->
                    val on = tab == target
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { tab = target }
                            .padding(vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(icon, null, tint = if (on) c.accent else c.ink3, modifier = Modifier.size(23.dp))
                        Text(label, style = JpText.meta, color = if (on) c.accent else c.ink3)
                    }
                }
            }
        },
    ) { inner ->
        // 당겨서 새로고침 — 위에서 아래로 끌면 동기화 트리거. 스피너는 짧게 보였다 사라짐(데이터는 흐름으로 갱신).
        var isRefreshing by remember { mutableStateOf(false) }
        LaunchedEffect(isRefreshing) {
            if (isRefreshing) { kotlinx.coroutines.delay(1200); isRefreshing = false }
        }
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { isRefreshing = true; vm.refresh() },
            modifier = Modifier.padding(inner).fillMaxSize(),
        ) {
            when (tab) {
                0 -> HomeScreen(
                    userName = userName,
                    todos = todos,
                    suggestions = suggestions,
                    parked = parked,
                    appointments = appointments,
                    onRefresh = { vm.refresh() },
                    onAccept = { vm.acceptSuggestion(it) },
                    onToAppt = { vm.suggestionToAppointment(it) },
                    onPark = { vm.parkSuggestion(it) },
                    onUnpark = { vm.unparkSuggestion(it) },
                    onDismiss = { vm.dismissSuggestion(it) },
                    onToggle = { id, done -> vm.toggleTodo(id, done) },
                    onSeeAll = { tab = 1 },
                )
                1 -> TodoScreen(
                    todos = todos,
                    onAdd = { vm.addTodo(userId, it) },
                    onToggle = { id, done -> vm.toggleTodo(id, done) },
                    onCompleteMany = { ids -> vm.completeMany(ids) },
                )
                2 -> RecordScreen(
                    summaries = summaries,
                    filteredSummaries = filteredSummaries,
                    openSummaryId = openSummaryId,
                    navTick = navTick,
                    query = recordQuery,
                    onQuery = { vm.setQuery(it) },
                    phoneOf = { eid -> phoneByEvent[eid] },
                    appointments = allAppointments,
                    rawEvents = rawEvents,
                    failed = failed,
                    groupByUser = isAdmin && showAllUsers,
                    selfUserId = userId,
                    onConfirm = { vm.confirmAppointment(it) },
                    onDeleteAppointment = { vm.deleteAppointment(it) },
                    onTogglePin = { id, pin -> vm.setSummaryPinned(id, pin) },
                    onRetry = { vm.retryFailed(it) },
                    onRefreshFailed = { vm.refreshFailed() },
                )
                3 -> SettingsScreen(
                    userName = userName,
                    userId = userId,
                    isAdmin = isAdmin,
                    showAllUsers = showAllUsers,
                    syncStatus = syncStatus,
                    onToggleShowAllUsers = { vm.setShowAllUsers(it) },
                    onOpenProfile = { showProfile = true },
                    onRefresh = { vm.refresh() },
                    onLogout = onLogout,
                )
            }
        }
    }
}

// ════════════════════════════ 오늘 (홈) ════════════════════════════
@Composable
private fun HomeScreen(
    userName: String?,
    todos: List<TodoEntity>,
    suggestions: List<TodoEntity>,
    parked: List<TodoEntity>,
    appointments: List<AppointmentEntity>,
    onRefresh: () -> Unit,
    onAccept: (String) -> Unit,
    onToAppt: (TodoEntity) -> Unit,
    onPark: (String) -> Unit,
    onUnpark: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onSeeAll: () -> Unit,
) {
    var mode by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        HomeModeSelector(mode) { mode = it }
        when (mode) {
            0 -> HomeBriefing(userName, todos, suggestions, appointments, onRefresh, onAccept, onToAppt, onDismiss, onToggle, onSeeAll)
            1 -> HomeTimeline(userName, todos, appointments, suggestions)
            else -> HomeInbox(todos, suggestions, parked, appointments, onAccept, onToAppt, onPark, onUnpark, onDismiss)
        }
    }
}

@Composable
private fun HomeModeSelector(mode: Int, onSelect: (Int) -> Unit) {
    val c = JpTheme.colors
    val labels = listOf("브리핑", "타임라인", "정리함")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(c.surface2)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = mode == i
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (on) Modifier.background(c.surface) else Modifier)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = JpText.meta, color = if (on) c.accent else c.ink2) }
        }
    }
}

@Composable
private fun HomeBriefing(
    userName: String?,
    todos: List<TodoEntity>,
    suggestions: List<TodoEntity>,
    appointments: List<AppointmentEntity>,
    onRefresh: () -> Unit,
    onAccept: (String) -> Unit,
    onToAppt: (TodoEntity) -> Unit,
    onDismiss: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onSeeAll: () -> Unit,
) {
    val c = JpTheme.colors
    val open = todos.filter { it.status == "open" }
    val todayLabel = remember {
        java.text.SimpleDateFormat("M월 d일 EEEE", java.util.Locale.KOREAN).format(java.util.Date())
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // 인사
        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UncleAvatar()
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(todayLabel, style = JpText.greet, color = c.ink2)
                    Text("좋은 하루예요, ${userName ?: "민호"}님", style = JpText.titleSm, color = c.ink)
                }
                Box(
                    Modifier.size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surface)
                        .border(1.dp, c.line, RoundedCornerShape(12.dp))
                        .clickable { onRefresh() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Notifications, "새로고침", tint = c.ink2, modifier = Modifier.size(19.dp)) }
            }
        }
        // 삼촌 브리핑 카드
        item {
            JpCard(background = c.accentSoft, border = false, radius = 24, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("삼촌의 오늘 브리핑", style = JpText.meta, color = c.accent)
                    Spacer(Modifier.height(7.dp))
                    Text(briefingLine(open.size, appointments.size, suggestions.size), style = JpText.card.copy(fontSize = JpText.card.fontSize, lineHeight = JpText.body.lineHeight), color = c.ink)
                    Spacer(Modifier.height(15.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatBox("할 일", open.size, Modifier.weight(1f))
                        StatBox("약속", appointments.size, Modifier.weight(1f))
                        StatBox("새로 찾음", suggestions.size, Modifier.weight(1f))
                    }
                }
            }
        }
        // 삼촌이 새로 찾음 — 확인 대기
        if (suggestions.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.Star, null, tint = c.accent, modifier = Modifier.size(15.dp))
                    SectionHeader("삼촌이 새로 찾았어요", Modifier.weight(1f))
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(c.accentSoft).padding(horizontal = 9.dp, vertical = 2.dp),
                    ) { Text("${suggestions.size}", style = JpText.tag, color = c.accent) }
                }
            }
            items(suggestions.take(2), key = { "sug_${it.id}" }) { s ->
                SuggestionCompact(s, onAccept = { onAccept(s.id) }, onDismiss = { onDismiss(s.id) })
                Spacer(Modifier.height(10.dp))
            }
        }
        // 오늘 할 일
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader("오늘 할 일", Modifier.weight(1f))
                Text("모두 보기", style = JpText.meta, color = c.accent, modifier = Modifier.clickable { onSeeAll() })
            }
        }
        if (open.isEmpty()) {
            item { EmptyLine("할 일이 비어 있어요. 삼촌이 통화·알림에서 찾으면 여기에 올려줄게요.") }
        } else {
            item {
                JpCard(Modifier.fillMaxWidth()) {
                    Column {
                        open.take(5).forEachIndexed { i, t ->
                            if (i > 0) HLine()
                            TodoRow(t, singleLine = true) { done -> onToggle(t.id, done) }
                        }
                    }
                }
            }
        }
        // 다가오는 약속 — 약속마다 카드 + 왼쪽 시간 블록
        if (appointments.isNotEmpty()) {
            item { SectionHeader("다가오는 약속", Modifier.padding(top = 22.dp, bottom = 11.dp)) }
            items(appointments.take(3), key = { "appt_${it.id}" }) { a ->
                HomeApptCard(a)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun HomeApptCard(a: AppointmentEntity) {
    val c = JpTheme.colors
    val ampmFmt = remember { java.text.SimpleDateFormat("a", java.util.Locale.KOREAN) }
    val timeFmt = remember { java.text.SimpleDateFormat("h:mm", java.util.Locale.KOREAN) }
    val date = remember(a.startAt) { java.util.Date(a.startAt) }
    JpCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.height(IntrinsicSize.Min).padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Column(
                Modifier.width(50.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(ampmFmt.format(date), style = JpText.tag, color = c.sage)
                Text(timeFmt.format(date), style = JpText.titleSm.copy(fontSize = 17.sp), color = c.ink)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
            Column(Modifier.weight(1f)) {
                Text(a.title, style = JpText.card, color = c.ink)
                if (!a.location.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.LocationOn, null, tint = c.ink3, modifier = Modifier.size(13.dp))
                        Text(a.location, style = JpText.meta, color = c.ink3)
                    }
                }
            }
            if (a.confirmed) Text("확정", style = JpText.tag, color = c.sage)
        }
    }
}

private fun briefingLine(todos: Int, appts: Int, sug: Int): String = buildString {
    if (sug > 0) append("어젯밤 기록에서 할 일 ${sug}개를 찾아 챙겨놨어요. ")
    when {
        appts > 0 && todos > 0 -> append("오늘 약속 ${appts}개, 할 일 ${todos}개가 기다려요.")
        appts > 0 -> append("오늘 약속이 ${appts}개 있어요.")
        todos > 0 -> append("오늘 챙길 할 일이 ${todos}개 있어요.")
        sug == 0 -> append("오늘은 잡힌 일이 없어요. 편한 하루 보내세요.")
    }
}

@Composable
private fun StatBox(label: String, value: Int, modifier: Modifier = Modifier) {
    val c = JpTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.surface).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text("$value", style = JpText.title.copy(fontSize = JpText.titleSm.fontSize), color = c.ink)
        Text(label, style = JpText.meta, color = c.ink2)
    }
}

// 브리핑 탭 — 새로 찾은 제안 간단 카드 (할 일로 추가 / x)
@Composable
private fun SuggestionCompact(s: TodoEntity, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val c = JpTheme.colors
    val sm = srcMeta(s.source)
    JpCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SrcTag(sm.first, s.relatedPerson, sm.second)
                Spacer(Modifier.weight(1f))
                Text(ago(s.createdAt), style = JpText.tag, color = c.ink3)
            }
            Spacer(Modifier.height(8.dp))
            Text(s.content, style = JpText.card, color = c.ink)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).height(36.dp)
                        .clip(RoundedCornerShape(11.dp)).background(c.accent)
                        .clickable(onClick = onAccept),
                    contentAlignment = Alignment.Center,
                ) { Text("할 일로 추가", style = JpText.body.copy(fontWeight = FontWeight.Bold), color = c.accentInk) }
                Box(
                    Modifier.size(36.dp)
                        .clip(RoundedCornerShape(11.dp)).background(c.surface2)
                        .border(1.dp, c.line, RoundedCornerShape(11.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, "넘기기", tint = c.ink2, modifier = Modifier.size(17.dp)) }
            }
        }
    }
}

// ════════════════════════════ 오늘 — 타임라인 (변형 B) ════════════════════════════
private class TLItem(val time: Long, val kind: String, val title: String, val person: String?, val source: String?, val sage: Boolean, val urgent: Boolean)

@Composable
private fun HomeTimeline(
    userName: String?,
    todos: List<TodoEntity>,
    appointments: List<AppointmentEntity>,
    suggestions: List<TodoEntity>,
) {
    val c = JpTheme.colors
    val open = todos.filter { it.status == "open" }
    val now = remember { System.currentTimeMillis() }
    // 오늘(KST) 0시~24시 범위 — "오늘의 흐름" 은 오늘 것만. (예전엔 전 기간을 다 쌓아 망가져 보였음)
    val todayStart = remember {
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul")).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val todayEnd = todayStart + 86_400_000L
    fun isToday(ms: Long): Boolean = ms in todayStart until todayEnd
    // 오늘 약속 + 오늘 마감인 할 일만 시간순으로
    val timed = remember(open, appointments) {
        val l = mutableListOf<TLItem>()
        appointments.filter { isToday(it.startAt) }.forEach { a -> l.add(TLItem(a.startAt, "약속", a.title, a.location ?: a.withPerson, "call", true, false)) }
        open.filter { it.dueAt != null && isToday(it.dueAt!!) }.forEach { t -> l.add(TLItem(t.dueAt!!, "마감", t.content, t.relatedPerson, t.source, false, (t.dueAt!! - now) in 0L..3_600_000L)) }
        l.sortedBy { it.time }
    }
    val nowIndex = timed.indexOfFirst { it.time >= now }
    // 시간 미정 — 오늘 들어온(생성된) 마감 없는 할 일
    val untimed = open.filter { it.dueAt == null && isToday(it.createdAt) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 30.dp),
    ) {
        item {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(todayGreet(), style = JpText.greet, color = c.ink2)
                Text("오늘의 흐름", style = JpText.titleSm.copy(fontSize = 27.sp), color = c.ink)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountChip(Icons.Filled.CheckCircle, "오늘 할 일", untimed.size + open.count { it.dueAt != null && isToday(it.dueAt!!) })
                CountChip(Icons.Filled.DateRange, "오늘 약속", appointments.count { isToday(it.startAt) })
                CountChip(Icons.Filled.Star, "검토", suggestions.size)
            }
        }
        if (timed.isEmpty() && untimed.isEmpty()) {
            item { EmptyLine("오늘 잡힌 일정이 없어요.") }
        }
        itemsIndexed(timed) { i, t ->
            if (i == nowIndex) NowMarker()
            TimelineRow(t)
        }
        if (timed.isNotEmpty() && nowIndex == -1) {
            item { NowMarker() }
        }
        if (untimed.isNotEmpty()) {
            item { SectionHeader("시간 미정", Modifier.padding(top = 18.dp, bottom = 10.dp)) }
            items(untimed, key = { "ut_${it.id}" }) { t ->
                JpCard(Modifier.fillMaxWidth(), background = c.surface2) {
                    Column(Modifier.padding(14.dp)) {
                        Text(t.content, style = JpText.card, color = c.ink)
                        Spacer(Modifier.height(5.dp))
                        val sm = srcMeta(t.source)
                        SrcTag(sm.first, t.relatedPerson, sm.second)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun NowMarker() {
    val c = JpTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c.accent))
        Text("지금", style = JpText.meta, color = c.accent)
        Box(Modifier.weight(1f).height(1.dp).background(c.accent))
    }
}

@Composable
private fun TimelineRow(t: TLItem) {
    val c = JpTheme.colors
    val ampm = remember { java.text.SimpleDateFormat("a", java.util.Locale.KOREAN) }
    val hm = remember { java.text.SimpleDateFormat("h:mm", java.util.Locale.KOREAN) }
    val date = remember(t.time) { java.util.Date(t.time) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.width(46.dp).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(ampm.format(date), style = JpText.tag, color = if (t.sage) c.sage else c.ink3)
            Text(hm.format(date), style = JpText.card, color = c.ink)
        }
        Box(Modifier.width(14.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
            Box(Modifier.padding(top = 14.dp).size(11.dp).clip(CircleShape).background(if (t.sage) c.sage else c.accent).border(2.dp, c.bg, CircleShape))
        }
        JpCard(
            Modifier.weight(1f).padding(bottom = 10.dp),
            background = if (t.sage) c.sageSoft else c.surface,
            border = !t.sage,
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (t.sage) c.sage else c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(t.kind, style = JpText.tag, color = if (t.sage) c.accentInk else c.accent)
                    }
                    if (t.urgent) Icon(Icons.Filled.Warning, null, tint = c.accent, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.height(7.dp))
                Text(t.title, style = JpText.card, color = c.ink)
                if (!t.person.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.LocationOn, null, tint = c.ink3, modifier = Modifier.size(13.dp))
                        Text(t.person, style = JpText.meta, color = c.ink3)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.CountChip(icon: ImageVector, label: String, n: Int) {
    val c = JpTheme.colors
    Row(
        Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(c.surface2).border(1.dp, c.line, RoundedCornerShape(999.dp)).padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, tint = c.ink2, modifier = Modifier.size(14.dp))
        Text("$label $n", style = JpText.meta, color = c.ink2)
    }
}

// ════════════════════════════ 오늘 — 받은 정리함 (변형 C) ════════════════════════════
@Composable
private fun HomeInbox(
    todos: List<TodoEntity>,
    suggestions: List<TodoEntity>,
    parked: List<TodoEntity>,
    appointments: List<AppointmentEntity>,
    onAccept: (String) -> Unit,
    onToAppt: (TodoEntity) -> Unit,
    onPark: (String) -> Unit,
    onUnpark: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    val c = JpTheme.colors
    val open = todos.filter { it.status == "open" }
    val done = todos.filter { it.status == "done" }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 30.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("받은 정리함", style = JpText.greet, color = c.ink2)
                    Text("삼촌이 찾았어요", style = JpText.titleSm.copy(fontSize = 22.sp), color = c.ink)
                }
                UncleAvatar()
            }
        }
        val cur = suggestions.firstOrNull()
        if (cur == null) {
            item {
                JpCard(Modifier.fillMaxWidth().padding(top = 12.dp), radius = 24) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Text("다 확인했어요. 새로 들어오면 여기에 모아둘게요.", style = JpText.body, color = c.ink3)
                    }
                }
            }
        } else {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    if (suggestions.size > 1) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).offset(y = 10.dp).height(40.dp).clip(RoundedCornerShape(24.dp)).background(c.surface2).border(1.dp, c.line, RoundedCornerShape(24.dp)))
                    }
                    TriageCard(cur, onAccept = { onAccept(cur.id) }, onToAppt = { onToAppt(cur) }, onPark = { onPark(cur.id) }, onDismiss = { onDismiss(cur.id) })
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("${suggestions.size}개 중 1번째", style = JpText.meta, color = c.ink3)
                }
            }
        }
        if (parked.isNotEmpty()) {
            item {
                SectionHeader("보류함 (${parked.size})", Modifier.padding(top = 24.dp, bottom = 11.dp))
            }
            items(parked, key = { "park_${it.id}" }) { p ->
                ParkedRow(p, onUnpark = { onUnpark(p.id) }, onDismiss = { onDismiss(p.id) })
                Spacer(Modifier.height(8.dp))
            }
        }
        item {
            SectionHeader("오늘 할 일", Modifier.padding(top = 24.dp, bottom = 11.dp))
        }
        item {
            JpCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp)) {
                    val total = open.size + done.size
                    val frac = if (total == 0) 0f else done.size.toFloat() / total
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${done.size}/$total 완료", style = JpText.card, color = c.ink, modifier = Modifier.weight(1f))
                        val next = appointments.firstOrNull()
                        if (next != null) {
                            val hm = remember { java.text.SimpleDateFormat("a h:mm", java.util.Locale.KOREAN) }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Filled.DateRange, null, tint = c.ink3, modifier = Modifier.size(13.dp))
                                Text("다음 ${hm.format(java.util.Date(next.startAt))}", style = JpText.meta, color = c.ink3)
                            }
                        }
                    }
                    Spacer(Modifier.height(11.dp))
                    ProgressBar(frac)
                }
            }
        }
    }
}

@Composable
private fun TriageCard(s: TodoEntity, onAccept: () -> Unit, onToAppt: () -> Unit, onPark: () -> Unit, onDismiss: () -> Unit) {
    val c = JpTheme.colors
    val sm = srcMeta(s.source)
    JpCard(Modifier.fillMaxWidth(), radius = 24, elevation = 14) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SrcTag(sm.first, s.relatedPerson, sm.second)
                Spacer(Modifier.weight(1f))
                Text(ago(s.createdAt), style = JpText.tag, color = c.ink3)
            }
            Spacer(Modifier.height(11.dp))
            Text(labelBySource(s.source), style = JpText.meta, color = c.ink2)
            Spacer(Modifier.height(6.dp))
            Text(s.content, style = JpText.titleSm.copy(fontSize = 21.sp), color = c.ink)
            if (!s.sourceExcerpt.isNullOrBlank()) {
                Spacer(Modifier.height(13.dp))
                Text("관련 발췌", style = JpText.tag, color = c.ink3)
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(13.dp)) {
                    Text("“${s.sourceExcerpt}”", style = JpText.body, color = c.ink2)
                }
            }
            Spacer(Modifier.height(15.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(13.dp)).background(c.accent).clickable(onClick = onAccept), contentAlignment = Alignment.Center) {
                    Text("할 일로", style = JpText.button, color = c.accentInk)
                }
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(13.dp)).background(c.sage).clickable(onClick = onToAppt), contentAlignment = Alignment.Center) {
                    Text("약속으로", style = JpText.button, color = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("보류", small = true, modifier = Modifier.weight(1f)) { onPark() }
                GhostButton("넘기기", small = true, modifier = Modifier.weight(1f)) { onDismiss() }
            }
        }
    }
}

// 보류함 한 줄 — 되돌리기(다시 검토) / 버리기
@Composable
private fun ParkedRow(s: TodoEntity, onUnpark: () -> Unit, onDismiss: () -> Unit) {
    val c = JpTheme.colors
    JpCard(Modifier.fillMaxWidth(), radius = 16) {
        Column(Modifier.padding(14.dp)) {
            Text(s.content, style = JpText.card, color = c.ink)
            Spacer(Modifier.height(4.dp))
            Text(ago(s.createdAt), style = JpText.tag, color = c.ink3)
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accent).clickable(onClick = onUnpark), contentAlignment = Alignment.Center) {
                    Text("다시 검토", style = JpText.button, color = c.accentInk)
                }
                GhostButton("버리기", small = true, modifier = Modifier.weight(1f)) { onDismiss() }
            }
        }
    }
}

@Composable
private fun ProgressBar(frac: Float) {
    val c = JpTheme.colors
    Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)).background(c.surface2)) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(c.accent))
    }
}

// ════════════════════════════ 할 일 ════════════════════════════
@Composable
private fun TodoScreen(
    todos: List<TodoEntity>,
    onAdd: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onCompleteMany: (List<String>) -> Unit = {},
) {
    val c = JpTheme.colors
    var input by remember { mutableStateOf("") }
    val open = todos.filter { it.status == "open" }
    val done = todos.filter { it.status == "done" }
    val openGroups = dayGroupsOf(open)                 // 생성일(KST)별, 최근 먼저
    val doneByDay = remember(done) { dayGroupsOf(done).associateBy { it.dayStart } }

    // 선택된 날짜 — 기본은 가장 최근(오늘 쪽). 그 날짜에 열린 할 일이 없어지면 첫 그룹으로.
    var selectedDay by rememberSaveable { mutableStateOf(-1L) }
    val chipDays = openGroups.map { it.dayStart }
    val effectiveDay = selectedDay.takeIf { it in chipDays } ?: chipDays.firstOrNull()
    val selGroup = openGroups.firstOrNull { it.dayStart == effectiveDay }
    val selDone = effectiveDay?.let { doneByDay[it]?.items }.orEmpty()
    var showDone by rememberSaveable { mutableStateOf(false) }
    val submit = { if (input.isNotBlank()) { onAdd(input.trim()); input = "" } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 30.dp),
    ) {
        item {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    remember { java.text.SimpleDateFormat("오늘 M월 d일", java.util.Locale.KOREAN).format(java.util.Date()) },
                    style = JpText.greet, color = c.ink2,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("할 일", style = JpText.title, color = c.ink, modifier = Modifier.weight(1f))
                    Text("열린 ${open.size} · 완료 ${done.size}", style = JpText.meta, color = c.sage)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("새 할 일 빠른 추가…", style = JpText.body, color = c.ink3) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = c.surface2,
                        unfocusedContainerColor = c.surface2,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = c.ink,
                        unfocusedTextColor = c.ink,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                Box(
                    Modifier.size(50.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (input.isNotBlank()) c.accent else c.surface2)
                        .clickable(enabled = input.isNotBlank()) { submit() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, "추가", tint = if (input.isNotBlank()) c.accentInk else c.ink3, modifier = Modifier.size(22.dp)) }
            }
        }
        // 날짜 선택칩 — 가로 스크롤. 고른 날짜의 할 일만 아래 표시(끝없이 길어지지 않게).
        if (openGroups.isNotEmpty()) {
            item(key = "daychips") {
                LazyRow(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(openGroups, key = { it.dayStart }) { g ->
                        TodoFilterChip(g.label, g.items.size, g.dayStart == effectiveDay) { selectedDay = g.dayStart }
                    }
                }
            }
        }
        if (selGroup == null) {
            item { EmptyLine("열린 할 일이 없어요. 삼촌이 통화·알림에서 찾으면 여기 올려줄게요.") }
        } else {
            item(key = "selhdr") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${selGroup.label} · ${selGroup.items.size}개", style = JpText.meta, color = c.ink2, modifier = Modifier.weight(1f))
                    if (selGroup.items.size > 1) {
                        Text(
                            "전체 완료",
                            style = JpText.meta,
                            color = c.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { onCompleteMany(selGroup.items.map { it.id }) }
                                .padding(horizontal = 11.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            item(key = "selcard") {
                JpCard(Modifier.fillMaxWidth()) {
                    Column {
                        selGroup.items.forEachIndexed { i, t ->
                            if (i > 0) HLine()
                            SwipeCompleteRow(onComplete = { onToggle(t.id, true) }) {
                                TodoRow(t) { d -> onToggle(t.id, d) }
                            }
                        }
                    }
                }
            }
        }
        // 이 날짜의 완료 항목 — 접힘 기본. 탭하면 펼침(완료 정리용).
        if (selDone.isNotEmpty()) {
            item(key = "donehdr") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp)
                        .clip(RoundedCornerShape(10.dp)).clickable { showDone = !showDone }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showDone) "완료한 항목 ${selDone.size}개 ▴" else "완료한 항목 ${selDone.size}개 보기 ▾",
                        style = JpText.meta, color = c.ink3, modifier = Modifier.weight(1f),
                    )
                }
            }
            if (showDone) {
                item(key = "donecard") {
                    Box(Modifier.alpha(0.85f).padding(top = 4.dp)) {
                        JpCard(Modifier.fillMaxWidth()) {
                            Column {
                                selDone.forEachIndexed { i, t ->
                                    if (i > 0) HLine()
                                    SwipeUndoRow(onUndo = { onToggle(t.id, false) }) {
                                        TodoRow(t) { d -> onToggle(t.id, d) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoFilterChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val c = JpTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (selected) c.accent else c.surface2)
            .then(if (selected) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(999.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$label $count", style = JpText.meta, color = if (selected) c.accentInk else c.ink2)
    }
}

@Composable
private fun TodoRow(t: TodoEntity, singleLine: Boolean = false, onToggle: (Boolean) -> Unit) {
    val c = JpTheme.colors
    val done = t.status == "done"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.clickable { onToggle(!done) }) { Tick(done) }
        Column(Modifier.weight(1f)) {
            Text(
                t.content,
                style = if (done) JpText.card.copy(textDecoration = TextDecoration.LineThrough) else JpText.card,
                color = if (done) c.ink3 else c.ink,
                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
            )
            val sm = srcMeta(t.source)
            Spacer(Modifier.height(5.dp))
            SrcTag(sm.first, t.relatedPerson, sm.second)
        }
    }
}

// ── 할 일: 생성일(KST)별 그룹 ──
private data class TodoDayGroup(val dayStart: Long, val label: String, val items: List<TodoEntity>)

private fun dayGroupsOf(todos: List<TodoEntity>): List<TodoDayGroup> {
    val kst = java.util.TimeZone.getTimeZone("Asia/Seoul")
    fun dayStartOf(ms: Long): Long = java.util.Calendar.getInstance(kst).apply {
        timeInMillis = ms
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val todayStart = dayStartOf(System.currentTimeMillis())
    val fmt = java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREAN).apply { timeZone = kst }
    return todos.groupBy { dayStartOf(it.createdAt) }
        .entries.sortedByDescending { it.key }
        .map { (day, list) ->
            val label = when ((todayStart - day) / 86_400_000L) {
                0L -> "오늘"
                1L -> "어제"
                else -> fmt.format(java.util.Date(day))
            }
            TodoDayGroup(day, label, list.sortedBy { it.createdAt })
        }
}

// 스와이프(오른쪽)로 완료. confirm 에서 false 반환 → 박스는 제자리, 항목은 완료되며 목록에서 빠짐.
@Composable
private fun SwipeCompleteRow(onComplete: () -> Unit, content: @Composable () -> Unit) {
    val c = JpTheme.colors
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.StartToEnd) { onComplete(); false } else false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(c.sage.copy(alpha = 0.16f)).padding(start = 22.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text("완료", style = JpText.meta, color = c.sage) }
        },
        // 컨텐츠는 카드색 불투명 박스로 감싼다 — 안 그러면 평소에도 뒤 배경("완료")이 비쳐 보임.
        content = { Box(Modifier.fillMaxWidth().background(c.surface)) { content() } },
    )
}

// 완료 항목을 오른쪽으로 스와이프해서 다시 '열림'으로 되돌림(실수로 완료한 거 복구).
@Composable
private fun SwipeUndoRow(onUndo: () -> Unit, content: @Composable () -> Unit) {
    val c = JpTheme.colors
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.StartToEnd) { onUndo(); false } else false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(c.ink3.copy(alpha = 0.14f)).padding(start = 22.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text("되돌리기", style = JpText.meta, color = c.ink2) }
        },
        content = { Box(Modifier.fillMaxWidth().background(c.surface)) { content() } },
    )
}

// ════════════════════════════ 기록 ════════════════════════════
@Composable
private fun RecordScreen(
    summaries: List<SummaryEntity>,
    filteredSummaries: List<SummaryEntity>,
    openSummaryId: String? = null,
    navTick: Int = 0,
    query: String,
    onQuery: (String) -> Unit,
    phoneOf: (String?) -> String?,
    appointments: List<AppointmentEntity>,
    rawEvents: List<EventEntity>,
    failed: List<FailedItemDto>,
    groupByUser: Boolean,
    selfUserId: Int,
    onConfirm: (String) -> Unit,
    onDeleteAppointment: (String) -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
    onRetry: (String) -> Unit,
    onRefreshFailed: () -> Unit,
) {
    var detailSummary by remember { mutableStateOf<SummaryEntity?>(null) }
    val c = JpTheme.colors

    // 알림에서 특정 통화 정리로 이동 요청이 오면, 목록에 그 항목이 들어오는 즉시 상세를 연다.
    var pendingOpenId by remember { mutableStateOf<String?>(null) }
    var lastOpenTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(navTick) {
        if (navTick != lastOpenTick) { lastOpenTick = navTick; pendingOpenId = openSummaryId }
    }
    LaunchedEffect(pendingOpenId, summaries) {
        val pid = pendingOpenId ?: return@LaunchedEffect
        summaries.firstOrNull { it.id == pid }?.let { detailSummary = it; pendingOpenId = null }
    }

    var seg by remember { mutableIntStateOf(0) }
    // 5칸 세그먼트라 라벨이 길면 줄바꿈됨 → 짧게 + 단일행 고정(아래 Text)
    val failLabel = if (failed.isNotEmpty()) "미처리 ${failed.size}" else "미처리"
    val segs = listOf("통화", failLabel, "보관")
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            Text("삼촌이 캡처·정리한 것", style = JpText.greet, color = c.ink2)
            Text("기록", style = JpText.title, color = c.ink)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(13.dp)).background(c.surface2).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            segs.forEachIndexed { idx, label ->
                val on = seg == idx
                Box(
                    Modifier.weight(1f).height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .then(if (on) Modifier.background(c.surface) else Modifier)
                        .clickable { seg = idx },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = JpText.meta, color = if (on) c.accent else c.ink2, maxLines = 1, softWrap = false) }
            }
        }
        Spacer(Modifier.height(14.dp))
        when (seg) {
            0 -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp)) {
                    TextField(
                        value = query,
                        onValueChange = onQuery,
                        placeholder = { Text("전화번호·이름·내용 검색", style = JpText.body, color = c.ink3) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Filled.Call, "통화 검색", tint = c.ink3, modifier = Modifier.size(20.dp)) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                Icon(
                                    Icons.Filled.Close, "지우기", tint = c.ink3,
                                    modifier = Modifier.size(20.dp).clickable { onQuery("") },
                                )
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = c.surface2,
                            unfocusedContainerColor = c.surface2,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = c.ink,
                            unfocusedTextColor = c.ink,
                        ),
                    )
                }
                if (query.isNotBlank() && filteredSummaries.isEmpty()) {
                    EmptyLine("\"$query\" 와 맞는 통화가 없어요.")
                } else {
                    SummaryList(filteredSummaries, groupByUser, selfUserId, onTogglePin, phoneOf) { detailSummary = it }
                }
            }
            1 -> FailedList(failed, onRetry, onRefreshFailed)
            else -> {
                val pinned = summaries.filter { it.pinned }
                if (pinned.isEmpty()) {
                    EmptyLine("보관함이 비어 있어요. 통화 정리에서 별을 눌러 담아두세요.")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pinned, key = { it.id }) { s -> SummaryCard(s, onTogglePin, null) { detailSummary = it } }
                    }
                }
            }
        }
    }

    detailSummary?.let { sel ->
        app.jongpal.jjongpal.ui.call.CallDetailScreen(sel) { detailSummary = null }
    }
    }
}

// 처리 막힌 항목 목록 — 건별 재시도 버튼. 진행 중(PROCESSING)은 서버에서 애초에 빠지므로 안 보임.
@Composable
private fun FailedList(
    failed: List<FailedItemDto>,
    onRetry: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    if (failed.isEmpty()) {
        EmptyLine("막힌 통화·알림이 없어요. 모두 정상 처리됐어요.")
        return
    }
    val pageSize = 20
    val pageCount = (failed.size + pageSize - 1) / pageSize
    var page by remember { mutableStateOf(0) }
    // 항목이 처리되며 줄어들면(팝) 현재 페이지가 범위를 벗어날 수 있음 — 끝 페이지로 당김
    val curPage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val pageItems = failed.drop(curPage * pageSize).take(pageSize)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("처리 중 막힌 항목 ${failed.size}건", style = JpText.meta, color = JpTheme.colors.ink2, modifier = Modifier.weight(1f))
                GhostButton("새로고침", small = true) { onRefresh() }
            }
        }
        items(pageItems, key = { "${it.event_id}_${it.stage}" }) { f -> FailedCard(f, onRetry) }
        if (pageCount > 1) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    GhostButton("이전", small = true, enabled = curPage > 0) { page = curPage - 1 }
                    Text(
                        "${curPage + 1} / $pageCount",
                        style = JpText.meta,
                        color = JpTheme.colors.ink2,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    GhostButton("다음", small = true, enabled = curPage < pageCount - 1) { page = curPage + 1 }
                }
            }
        }
    }
}

// 서버 원본 에러(영어)를 사용자용 한글 설명으로 바꿔줌
private fun friendlyFailReason(stage: String, error: String?): String {
    val e = error.orEmpty()
    return when {
        e.contains("Invalid data found") || e.contains("Invalid argument") ->
            "음성 파일이 손상돼 처리할 수 없어요."
        e.contains("duplicate key") ->
            "이미 처리된 항목이에요."
        e.contains("timeout") ->
            "처리 시간이 초과됐어요. 다시 시도해 보세요."
        e.contains("Expecting value") || e.contains("Extra data") ||
            e.contains("claude") || e.contains("Exec format") ->
            "정리 작업이 결과를 못 냈어요. 다시 시도하면 처리돼요."
        else -> when (stage) {
            "transcript" -> "받아쓰기 중 문제가 생겼어요. 다시 시도해 보세요."
            "summary" -> "정리 중 문제가 생겼어요. 다시 시도해 보세요."
            "event" -> "알림 정리 중 문제가 생겼어요. 다시 시도해 보세요."
            else -> "처리 중 문제가 생겼어요. 다시 시도해 보세요."
        }
    }
}

// 음성 파일 손상 등 재시도해도 못 고치는 건 — 재시도 버튼 숨김
private fun isUnrecoverable(error: String?): Boolean {
    val e = error.orEmpty()
    return e.contains("Invalid data found") || e.contains("Invalid argument")
}

@Composable
private fun FailedCard(f: FailedItemDto, onRetry: (String) -> Unit) {
    val c = JpTheme.colors
    val unrecoverable = isUnrecoverable(f.error_message)
    val stageLabel = when (f.stage) {
        "transcript" -> "받아쓰기 실패"
        "summary" -> "정리 실패"
        "event" -> "알림 정리 실패"
        else -> "처리 실패"
    }
    JpCard(Modifier.fillMaxWidth(), radius = 18) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.surface2).padding(horizontal = 9.dp, vertical = 4.dp)) {
                    Text(stageLabel, style = JpText.tag, color = c.ink2)
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(
                f.label?.takeIf { it.isNotBlank() } ?: "(제목 없음)",
                style = JpText.card,
                color = c.ink,
            )
            Spacer(Modifier.height(7.dp))
            Text(friendlyFailReason(f.stage, f.error_message), style = JpText.tag, color = c.ink3)
            Spacer(Modifier.height(13.dp))
            if (unrecoverable) {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(13.dp)).background(c.surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("처리 불가", style = JpText.button, color = c.ink3)
                }
            } else {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(13.dp)).background(c.accent).clickable { onRetry(f.event_id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("재시도", style = JpText.button, color = c.accentInk)
                }
            }
        }
    }
}

@Composable
private fun SummaryList(summaries: List<SummaryEntity>, groupByUser: Boolean, selfUserId: Int, onTogglePin: (String, Boolean) -> Unit, phoneOf: (String?) -> String? = { null }, onOpenDetail: (SummaryEntity) -> Unit = {}) {
    if (summaries.isEmpty()) { EmptyLine("아직 정리된 통화가 없어요."); return }
    if (groupByUser) {
        val grouped = summaries.groupBy { it.userId }.toSortedMap()
        val expanded = remember(grouped.keys) {
            mutableStateMapOf<Int, Boolean>().apply { grouped.keys.forEach { put(it, it == selfUserId) } }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            grouped.forEach { (uid, list) ->
                item(key = "h_$uid") {
                    val label = if (uid == selfUserId) "본인" else "사용자 $uid"
                    JpCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().clickable { expanded[uid] = !(expanded[uid] ?: false) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("$label · ${list.size}건", style = JpText.card, modifier = Modifier.weight(1f))
                            Text(if (expanded[uid] == true) "접기" else "펴기", style = JpText.meta, color = JpTheme.colors.accent)
                        }
                    }
                }
                if (expanded[uid] == true) {
                    items(list, key = { it.id }) { s -> SummaryCard(s, onTogglePin, phoneOf(s.eventId), onOpenDetail) }
                }
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(summaries, key = { it.id }) { s -> SummaryCard(s, onTogglePin, phoneOf(s.eventId), onOpenDetail) }
        item { ShieldNotice("음성은 텍스트로 바꾼 뒤 분석하고, 원본 녹음 파일은 폰에만 남아요.") }
    }
}

@Composable
private fun ShieldNotice(text: String) {
    val c = JpTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(Icons.Filled.Lock, null, tint = c.ink3, modifier = Modifier.size(14.dp))
        Text(text, style = JpText.meta, color = c.ink3)
    }
}

private fun formatPhone(raw: String): String {
    val d = raw.filter { it.isDigit() }
    return when {
        d.length == 11 && d.startsWith("010") -> "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
        d.length == 10 -> "${d.substring(0, 3)}-${d.substring(3, 6)}-${d.substring(6)}"
        else -> raw
    }
}

@Composable
private fun SummaryCard(s: SummaryEntity, onTogglePin: (String, Boolean) -> Unit, phone: String? = null, onOpenDetail: (SummaryEntity) -> Unit = {}) {
    val c = JpTheme.colors
    val extracted = remember(s.rawJson) { parseExtracted(s.rawJson) }
    JpCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(c.sageSoft).padding(horizontal = 15.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.sage), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Call, null, tint = Color.White, modifier = Modifier.size(19.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (!phone.isNullOrBlank()) formatPhone(phone) else "통화 정리", style = JpText.card, color = c.ink)
                    Text(ago(s.createdAt), style = JpText.meta, color = c.ink2)
                }
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenDetail(s) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, "원문 보기·듣기", tint = c.ink3, modifier = Modifier.size(22.dp))
                }
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                        .clickable { onTogglePin(s.id, !s.pinned) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Star, if (s.pinned) "보관함에서 빼기" else "보관함에 담기", tint = if (s.pinned) c.accent else c.ink3, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.padding(15.dp)) {
                MarkdownText(s.summary ?: "(요약 없음)")
                if (extracted.isNotEmpty()) {
                    Spacer(Modifier.height(13.dp))
                    Text("여기서 찾은 할 일 · 약속", style = JpText.section, color = c.ink2)
                    Spacer(Modifier.height(8.dp))
                    extracted.forEach { (kind, text) ->
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 6.dp)
                                .clip(RoundedCornerShape(11.dp)).background(c.surface2)
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                if (kind == "appt") Icons.Filled.DateRange else Icons.Filled.CheckCircle,
                                null, tint = c.ink3, modifier = Modifier.size(15.dp),
                            )
                            Text(text, style = JpText.body, color = c.ink)
                        }
                    }
                }
            }
        }
    }
}

private fun parseExtracted(raw: String): List<Pair<String, String>> {
    return try {
        val o = org.json.JSONObject(raw)
        val out = mutableListOf<Pair<String, String>>()
        for (key in listOf("todos", "action_items")) {
            val arr = o.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                val text = if (item is org.json.JSONObject) item.optString("content", item.optString("title", "")) else item.toString()
                if (text.isNotBlank()) out.add("todo" to text)
            }
        }
        o.optJSONArray("appointments")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                val text = if (item is org.json.JSONObject) item.optString("title", item.optString("content", "")) else item.toString()
                if (text.isNotBlank()) out.add("appt" to text)
            }
        }
        out.take(6)
    } catch (e: Exception) {
        emptyList()
    }
}

// 약속 날짜 구간 — 오늘 자정 기준으로 묶음
private enum class ApptBucket(val label: String) {
    PAST("지난"), TODAY("오늘"), TOMORROW("내일"), THIS_WEEK("이번 주"), LATER("이후")
}

private fun apptBucket(startAt: Long, now: Long): ApptBucket {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis
    val dayMs = 24 * 60 * 60 * 1000L
    val tomorrowStart = todayStart + dayMs
    val dayAfterTomorrow = todayStart + 2 * dayMs
    val weekEnd = todayStart + 7 * dayMs
    return when {
        startAt < todayStart -> ApptBucket.PAST
        startAt < tomorrowStart -> ApptBucket.TODAY
        startAt < dayAfterTomorrow -> ApptBucket.TOMORROW
        startAt < weekEnd -> ApptBucket.THIS_WEEK
        else -> ApptBucket.LATER
    }
}

private enum class ApptFilter(val label: String) {
    UPCOMING("다가오는"), ALL("전체"), CONFIRMED("확정"), UNCONFIRMED("미확정")
}

@Composable
private fun AppointmentList(
    appointments: List<AppointmentEntity>,
    onConfirm: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val c = JpTheme.colors
    val now = remember { System.currentTimeMillis() }
    var filter by remember { mutableStateOf(ApptFilter.UPCOMING) }
    var pendingDelete by remember { mutableStateOf<AppointmentEntity?>(null) }

    val filtered = remember(appointments, filter) {
        when (filter) {
            ApptFilter.UPCOMING -> appointments.filter { it.startAt >= now }
            ApptFilter.ALL -> appointments
            ApptFilter.CONFIRMED -> appointments.filter { it.confirmed }
            ApptFilter.UNCONFIRMED -> appointments.filter { !it.confirmed }
        }
    }
    // 다가오는: 가까운 순. 그 외(지난 포함): 최신 약속 위로.
    val sorted = remember(filtered, filter) {
        if (filter == ApptFilter.UPCOMING) filtered.sortedBy { it.startAt }
        else filtered.sortedByDescending { it.startAt }
    }
    // 날짜 구간별 묶기 (구간 순서 유지)
    val sections = remember(sorted) {
        sorted.groupBy { apptBucket(it.startAt, now) }
            .toList()
            .sortedBy { it.first.ordinal }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ApptFilter.entries.forEach { f ->
                val on = filter == f
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (on) c.accent else c.surface2)
                        .clickable { filter = f }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                ) { Text(f.label, style = JpText.tag, color = if (on) c.accentInk else c.ink2) }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (sorted.isEmpty()) {
            EmptyLine("해당하는 약속이 없어요.")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEach { (bucket, items) ->
                    item(key = "sec_${bucket.name}") {
                        Text(
                            bucket.label,
                            style = JpText.meta,
                            color = c.ink2,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                    }
                    items(items, key = { it.id }) { a ->
                        JpCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(15.dp)) {
                                AppointmentRow(a)
                                if (!a.sourceExcerpt.isNullOrBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("“${a.sourceExcerpt}”", style = JpText.body, color = c.ink2)
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!a.confirmed) {
                                        GhostButton("확정하기", small = true) { onConfirm(a.id) }
                                    }
                                    GhostButton("지우기", small = true, danger = true) { pendingDelete = a }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("약속 지우기", style = JpText.card, color = c.ink) },
            text = { Text("“${target.title}” 약속을 지울까요?", style = JpText.body, color = c.ink2) },
            confirmButton = {
                Text(
                    "지우기",
                    style = JpText.card,
                    color = c.danger,
                    modifier = Modifier.clickable { onDelete(target.id); pendingDelete = null }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            },
            dismissButton = {
                Text(
                    "취소",
                    style = JpText.card,
                    color = c.ink2,
                    modifier = Modifier.clickable { pendingDelete = null }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            },
            containerColor = c.surface,
        )
    }
}

@Composable
private fun AppointmentRow(a: AppointmentEntity) {
    val c = JpTheme.colors
    val fmt = remember { java.text.SimpleDateFormat("M월 d일 a h:mm", java.util.Locale.KOREAN) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(a.title, style = JpText.card, color = c.ink)
            Spacer(Modifier.height(4.dp))
            val meta = buildString {
                append(fmt.format(java.util.Date(a.startAt)))
                if (!a.location.isNullOrBlank()) append(" · ${a.location}")
                if (!a.withPerson.isNullOrBlank()) append(" · ${a.withPerson}")
            }
            Text(meta, style = JpText.body, color = c.ink2)
        }
        if (a.confirmed) Text("확정", style = JpText.tag, color = c.sage)
    }
}

@Composable
private fun RawEventList(events: List<EventEntity>) {
    if (events.isEmpty()) { EmptyLine("아직 들어온 알림·통화가 없어요."); return }
    val c = JpTheme.colors
    val fmt = remember { java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.KOREAN) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(events, key = { it.id }) { e ->
            JpCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp)) {
                    val typeLabel = when (e.type) { "notification" -> "알림"; "call" -> "통화"; else -> e.type }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SrcTag(typeLabel, null, sage = e.type == "call")
                        Spacer(Modifier.weight(1f))
                        Text(fmt.format(java.util.Date(e.timestamp)), style = JpText.tag, color = c.ink3)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(e.title ?: "(제목 없음)", style = JpText.card, color = c.ink)
                    if (!e.content.isNullOrBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(e.content.take(160), style = JpText.body, color = c.ink2)
                    }
                }
            }
        }
    }
}

// ════════════════════════════ 내 정보 ════════════════════════════
@Composable
private fun ProfileScreen(
    userName: String?,
    userId: Int,
    isAdmin: Boolean,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    val c = JpTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ArrowBack, "뒤로", tint = c.ink2,
                modifier = Modifier.size(24.dp).clickable { onBack() },
            )
            Spacer(Modifier.width(10.dp))
            Text("내 정보", style = JpText.titleSm, color = c.ink)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                JpCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(60.dp).clip(RoundedCornerShape(19.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Person, null, tint = c.accentInk, modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(userName ?: "사용자", style = JpText.card.copy(fontSize = 18.sp), color = c.ink)
                            Text(if (isAdmin) "어드민" else "가족 구성원", style = JpText.meta, color = c.ink2)
                        }
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("가족 단위", style = JpText.tag, color = c.accent)
                        }
                    }
                }
            }
            item { SectionHeader("계정 정보", Modifier.padding(top = 6.dp)) }
            item {
                JpCard(Modifier.fillMaxWidth()) {
                    Column {
                        SettingRow(Icons.Filled.Person, "이름", "표시 이름", null) {
                            Text(userName ?: "-", style = JpText.card, color = c.ink2)
                        }
                        HLine()
                        SettingRow(Icons.Filled.Lock, "역할", "계정 권한", null) {
                            Text(if (isAdmin) "어드민" else "가족", style = JpText.card, color = c.ink2)
                        }
                        HLine()
                        SettingRow(Icons.Filled.List, "사용자 번호", "계정 식별자", null) {
                            Text("#$userId", style = JpText.card, color = c.ink2)
                        }
                    }
                }
            }
            item { SectionHeader("계정", Modifier.padding(top = 6.dp)) }
            item {
                JpCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().clickable { onLogout() }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.ArrowForward, null, tint = c.danger, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(11.dp))
                        Text("로그아웃", style = JpText.card, color = c.danger, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ════════════════════════════ 설정 ════════════════════════════
@Composable
private fun SettingsScreen(
    userName: String?,
    userId: Int,
    isAdmin: Boolean,
    showAllUsers: Boolean,
    syncStatus: SyncStatus,
    onToggleShowAllUsers: (Boolean) -> Unit,
    onOpenProfile: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    val c = JpTheme.colors
    val ctx = LocalContext.current
    var notif by remember { mutableStateOf(isNotificationAccessGranted(ctx)) }
    var audio by remember { mutableStateOf(hasMediaAudioPermission(ctx)) }
    var batt by remember { mutableStateOf(isBatteryUnrestricted(ctx)) }
    var files by remember { mutableStateOf(hasAllFilesAccess(ctx)) }
    var phone by remember { mutableStateOf(hasPhoneStatePermission(ctx)) }
    var callLog by remember { mutableStateOf(hasCallLogPermission(ctx)) }
    val refreshPerms = {
        notif = isNotificationAccessGranted(ctx)
        audio = hasMediaAudioPermission(ctx)
        batt = isBatteryUnrestricted(ctx)
        files = hasAllFilesAccess(ctx)
        phone = hasPhoneStatePermission(ctx)
        callLog = hasCallLogPermission(ctx)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshPerms() }
    val battLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshPerms() }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshPerms() }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPerms() }
    val phoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPerms() }
    val callLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPerms() }
    val grantedCount = listOf(audio, batt, files, phone, callLog).count { it }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("설정", style = JpText.title, color = c.ink, modifier = Modifier.padding(vertical = 6.dp)) }
        item {
            JpCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Person, null, tint = c.accentInk, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(userName ?: "사용자", style = JpText.card, color = c.ink)
                        Text("사용자 $userId · ${if (isAdmin) "어드민" else "가족"}", style = JpText.meta, color = c.ink2)
                    }
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("가족 단위", style = JpText.tag, color = c.accent)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("권한", Modifier.weight(1f))
                Text("$grantedCount / 5 허용됨", style = JpText.meta, color = if (grantedCount == 5) c.sage else c.ink2)
            }
        }
        item {
            JpCard(Modifier.fillMaxWidth()) {
                Column {
                    SettingRow(Icons.Filled.Phone, "통화 녹음 폴더", "음성 파일 감시", {
                        audioLauncher.launch(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
                            else Manifest.permission.READ_EXTERNAL_STORAGE
                        )
                    }) { PermStat(audio) }
                    HLine()
                    SettingRow(Icons.Filled.Phone, "전화 상태 확인", "통화 종료 감지 후 즉시 업로드", {
                        phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                    }) { PermStat(phone) }
                    HLine()
                    SettingRow(Icons.Filled.Call, "통화 상대 번호 읽기", "통화기록에서 번호 확보 → 번호 검색", {
                        callLogLauncher.launch(Manifest.permission.READ_CALL_LOG)
                    }) { PermStat(callLog) }
                    HLine()
                    SettingRow(Icons.Filled.Settings, "배터리 최적화 제외", "백그라운드 안정 동작", {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            battLauncher.launch(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
                            )
                        }
                    }) { PermStat(batt) }
                    HLine()
                    SettingRow(Icons.Filled.Lock, "모든 파일 접근", "통화 녹음 폴더 권한 필요", {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                filesLauncher.launch(
                                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
                                )
                            } catch (_: Exception) {
                                filesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            }
                        }
                    }) { PermStat(files) }
                }
            }
        }
        item { SectionHeader("동기화", Modifier.padding(top = 6.dp)) }
        item {
            JpCard(Modifier.fillMaxWidth()) {
                Column {
                    val healthy = syncStatus.isHealthy()
                    val syncDesc = when {
                        !syncStatus.everSynced -> "아직 동기화 전 · 눌러서 시도"
                        healthy -> "동기화 사용 중 · ${ago(syncStatus.lastSuccessAt)} · 눌러서 새로고침"
                        else -> "연결 끊김 · 마지막 동기화 ${ago(syncStatus.lastSuccessAt)} · 눌러서 재시도"
                    }
                    SettingRow(Icons.Filled.Refresh, "PC 연결", syncDesc, onRefresh) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (healthy) c.sage else c.danger))
                    }
                    if (isAdmin) {
                        HLine()
                        SettingRow(Icons.Filled.Person, "모든 사용자 데이터 보기", "본인 외 기록도 함께 표시", null) {
                            Switch(
                                checked = showAllUsers, onCheckedChange = onToggleShowAllUsers,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White, checkedTrackColor = c.accent,
                                    uncheckedThumbColor = Color.White, uncheckedTrackColor = c.lineStrong,
                                    checkedBorderColor = Color.Transparent, uncheckedBorderColor = Color.Transparent,
                                ),
                            )
                        }
                    }
                }
            }
        }
        item { SectionHeader("계정", Modifier.padding(top = 6.dp)) }
        item {
            JpCard(Modifier.fillMaxWidth()) {
                Column {
                    SettingRow(Icons.Filled.Person, "내 정보", "이름 · 역할", onOpenProfile) {
                        Icon(Icons.Filled.KeyboardArrowRight, null, tint = c.ink3, modifier = Modifier.size(20.dp))
                    }
                    HLine()
                    Row(Modifier.fillMaxWidth().clickable { onLogout() }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.ArrowForward, null, tint = c.danger, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(11.dp))
                        Text("로그아웃", style = JpText.card, color = c.danger, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, sub: String, onClick: (() -> Unit)?, trailing: @Composable () -> Unit) {
    val c = JpTheme.colors
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.ink2, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = JpText.card, color = c.ink)
            Text(sub, style = JpText.meta, color = c.ink2)
        }
        trailing()
    }
}

@Composable
private fun PermStat(ok: Boolean) {
    val c = JpTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (ok) c.sageSoft else c.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(if (ok) "허용" else "설정", style = JpText.tag, color = if (ok) c.sage else c.accent)
    }
}

// ════════════════════════════ 공용 ════════════════════════════
private fun srcMeta(source: String?): Pair<String, Boolean> = when (source) {
    "call" -> "통화" to true
    "notification" -> "알림" to false
    "manual" -> "직접" to false
    "ai_suggestion" -> "삼촌" to false
    else -> (source ?: "기타") to false
}

private fun labelBySource(source: String?): String = when (source) {
    "call" -> "통화에서 이걸 들었어요"
    "notification" -> "알림에서 이걸 봤어요"
    else -> "기록에서 이걸 찾았어요"
}

private fun ago(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 1 -> "방금"
        m < 60 -> "${m}분 전"
        m < 1440 -> "${m / 60}시간 전"
        else -> "${m / 1440}일 전"
    }
}

private fun todayGreet(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val word = when (h) { in 5..11 -> "좋은 아침이에요"; in 12..17 -> "좋은 오후예요"; else -> "편안한 저녁이에요" }
    val date = java.text.SimpleDateFormat("M월 d일 EEEE", java.util.Locale.KOREAN).format(java.util.Date())
    return "$word · $date"
}

@Composable
private fun EmptyLine(msg: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(msg, style = JpText.body, color = JpTheme.colors.ink3)
    }
}

// 요약 마크다운 가벼운 렌더 (### 헤더 / - [ ] / - 불릿 / **bold**)
@Composable
fun MarkdownText(raw: String, maxChars: Int = 4_000) {
    val c = JpTheme.colors
    val text = if (raw.length > maxChars) raw.substring(0, maxChars) + "\n… (이하 생략)" else raw
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("### ") -> Text(line.removePrefix("### "), style = JpText.section, color = c.ink2, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                line.startsWith("- [ ] ") -> Text("☐  " + line.removePrefix("- [ ] "), style = JpText.body, color = c.ink)
                line.startsWith("- [x] ") || line.startsWith("- [X] ") -> Text("☑  " + line.removePrefix("- ").drop(4), style = JpText.body, color = c.ink2)
                line.startsWith("- ") -> Text(buildAnnotatedString { append("•  "); append(applyInlineBold(line.removePrefix("- "))) }, style = JpText.body, color = c.ink)
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                else -> Text(applyInlineBold(line), style = JpText.body, color = c.ink)
            }
        }
    }
}

private fun applyInlineBold(src: String) = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        val open = src.indexOf("**", i)
        if (open < 0) { append(src.substring(i)); break }
        append(src.substring(i, open))
        val close = src.indexOf("**", open + 2)
        if (close < 0) { append(src.substring(open)); break }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(src.substring(open + 2, close)) }
        i = close + 2
    }
}
