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
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import app.jongpal.jjongpal.ui.permission.hasMediaAudioPermission
import app.jongpal.jjongpal.ui.permission.isBatteryUnrestricted
import app.jongpal.jjongpal.ui.permission.isNotificationAccessGranted
import app.jongpal.jjongpal.ui.theme.JpText
import app.jongpal.jjongpal.ui.theme.JpTheme

private data class NavItem(val label: String, val icon: ImageVector)

@Composable
fun MainScreen(
    onLogout: () -> Unit,
    userName: String?,
    userId: Int,
    vm: MainViewModel = hiltViewModel(),
) {
    val c = JpTheme.colors
    var tab by remember { mutableIntStateOf(0) }
    var showProfile by remember { mutableStateOf(false) }

    val todos by vm.todos.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val summaries by vm.summaries.collectAsState()
    val appointments by vm.appointments.collectAsState()
    val rawEvents by vm.rawEvents.collectAsState()
    val showAllUsers by vm.showAllUsers.collectAsState()
    val isAdmin = vm.isAdmin

    if (showProfile) {
        ProfileScreen(userName = userName, userId = userId, isAdmin = isAdmin, onBack = { showProfile = false }, onLogout = onLogout)
        return
    }

    val items = listOf(
        NavItem("오늘", Icons.Filled.Home),
        NavItem("할 일", Icons.Filled.CheckCircle),
        NavItem("기록", Icons.Filled.Phone),
        NavItem("설정", Icons.Filled.Settings),
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
                items.forEachIndexed { idx, item ->
                    val on = tab == idx
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { tab = idx }
                            .padding(vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(item.icon, null, tint = if (on) c.accent else c.ink3, modifier = Modifier.size(23.dp))
                        Text(item.label, style = JpText.meta, color = if (on) c.accent else c.ink3)
                    }
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(
                    userName = userName,
                    todos = todos,
                    suggestions = suggestions,
                    appointments = appointments,
                    onRefresh = { vm.refresh() },
                    onAccept = { vm.acceptSuggestion(it) },
                    onToAppt = { vm.suggestionToAppointment(it) },
                    onDismiss = { vm.dismissSuggestion(it) },
                    onToggle = { id, done -> vm.toggleTodo(id, done) },
                    onSeeAll = { tab = 1 },
                )
                1 -> TodoScreen(
                    todos = todos,
                    onAdd = { vm.addTodo(userId, it) },
                    onToggle = { id, done -> vm.toggleTodo(id, done) },
                )
                2 -> RecordScreen(
                    summaries = summaries,
                    appointments = appointments,
                    rawEvents = rawEvents,
                    groupByUser = isAdmin && showAllUsers,
                    selfUserId = userId,
                    onConfirm = { vm.confirmAppointment(it) },
                )
                3 -> SettingsScreen(
                    userName = userName,
                    userId = userId,
                    isAdmin = isAdmin,
                    showAllUsers = showAllUsers,
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
    appointments: List<AppointmentEntity>,
    onRefresh: () -> Unit,
    onAccept: (String) -> Unit,
    onToAppt: (TodoEntity) -> Unit,
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
            else -> HomeInbox(todos, suggestions, appointments, onAccept, onToAppt, onDismiss)
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
    val timed = remember(open, appointments) {
        val l = mutableListOf<TLItem>()
        appointments.forEach { a -> l.add(TLItem(a.startAt, "약속", a.title, a.location ?: a.withPerson, "call", true, false)) }
        open.filter { it.dueAt != null }.forEach { t -> l.add(TLItem(t.dueAt!!, "마감", t.content, t.relatedPerson, t.source, false, (t.dueAt!! - now) in 0L..3_600_000L)) }
        l.sortedBy { it.time }
    }
    val nowIndex = timed.indexOfFirst { it.time >= now }
    val untimed = open.filter { it.dueAt == null }
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
                CountChip(Icons.Filled.CheckCircle, "할 일", open.size)
                CountChip(Icons.Filled.DateRange, "약속", appointments.size)
                CountChip(Icons.Filled.Star, "새로", suggestions.size)
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
    appointments: List<AppointmentEntity>,
    onAccept: (String) -> Unit,
    onToAppt: (TodoEntity) -> Unit,
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
                    TriageCard(cur, onAccept = { onAccept(cur.id) }, onToAppt = { onToAppt(cur) }, onDismiss = { onDismiss(cur.id) })
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("${suggestions.size}개 중 1번째", style = JpText.meta, color = c.ink3)
                }
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
private fun TriageCard(s: TodoEntity, onAccept: () -> Unit, onToAppt: () -> Unit, onDismiss: () -> Unit) {
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
            GhostButton("지금은 넘기기", small = true, modifier = Modifier.fillMaxWidth()) { onDismiss() }
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
) {
    val c = JpTheme.colors
    var input by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    val dayStart = remember {
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val dayEnd = dayStart + 86_400_000L
    val weekEnd = dayStart + 7 * 86_400_000L
    fun match(t: TodoEntity): Boolean = when (filter) {
        0 -> t.dueAt == null || t.dueAt!! in dayStart until dayEnd
        1 -> t.dueAt == null || t.dueAt!! < weekEnd
        else -> true
    }
    val open = todos.filter { it.status == "open" && match(it) }
    val done = todos.filter { it.status == "done" && match(it) }
    val total = open.size + done.size
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
                    Text("${done.size}/$total 완료", style = JpText.meta, color = c.sage)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TodoFilterChip("오늘", todos.count { it.status == "open" && (it.dueAt == null || it.dueAt!! in dayStart until dayEnd) }, filter == 0) { filter = 0 }
                TodoFilterChip("이번주", todos.count { it.status == "open" && (it.dueAt == null || it.dueAt!! < weekEnd) }, filter == 1) { filter = 1 }
                TodoFilterChip("전체", todos.count { it.status == "open" }, filter == 2) { filter = 2 }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        if (open.isEmpty()) {
            item { EmptyLine("이 조건에 맞는 할 일이 없어요.") }
        } else {
            item {
                JpCard(Modifier.fillMaxWidth()) {
                    Column {
                        open.forEachIndexed { i, t ->
                            if (i > 0) HLine()
                            TodoRow(t) { d -> onToggle(t.id, d) }
                        }
                    }
                }
            }
        }
        if (done.isNotEmpty()) {
            item { SectionHeader("완료됨 · ${done.size}", Modifier.padding(top = 22.dp, bottom = 11.dp)) }
            item {
                Box(Modifier.alpha(0.85f)) {
                    JpCard(Modifier.fillMaxWidth()) {
                        Column {
                            done.forEachIndexed { i, t ->
                                if (i > 0) HLine()
                                TodoRow(t) { d -> onToggle(t.id, d) }
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

// ════════════════════════════ 기록 ════════════════════════════
@Composable
private fun RecordScreen(
    summaries: List<SummaryEntity>,
    appointments: List<AppointmentEntity>,
    rawEvents: List<EventEntity>,
    groupByUser: Boolean,
    selfUserId: Int,
    onConfirm: (String) -> Unit,
) {
    val c = JpTheme.colors
    var seg by remember { mutableIntStateOf(0) }
    val segs = listOf("통화 정리", "약속", "원본 알림")
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
                ) { Text(label, style = JpText.meta, color = if (on) c.accent else c.ink2) }
            }
        }
        Spacer(Modifier.height(14.dp))
        when (seg) {
            0 -> SummaryList(summaries, groupByUser, selfUserId)
            1 -> AppointmentList(appointments, onConfirm)
            else -> RawEventList(rawEvents)
        }
    }
}

@Composable
private fun SummaryList(summaries: List<SummaryEntity>, groupByUser: Boolean, selfUserId: Int) {
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
                    items(list, key = { it.id }) { s -> SummaryCard(s) }
                }
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(summaries, key = { it.id }) { s -> SummaryCard(s) }
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

@Composable
private fun SummaryCard(s: SummaryEntity) {
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
                    Text("통화 정리", style = JpText.card, color = c.ink)
                    Text(ago(s.createdAt), style = JpText.meta, color = c.ink2)
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

@Composable
private fun AppointmentList(appointments: List<AppointmentEntity>, onConfirm: (String) -> Unit) {
    if (appointments.isEmpty()) { EmptyLine("아직 약속이 없어요."); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(appointments, key = { it.id }) { a ->
            JpCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp)) {
                    AppointmentRow(a)
                    if (!a.sourceExcerpt.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("“${a.sourceExcerpt}”", style = JpText.body, color = JpTheme.colors.ink2)
                    }
                    if (!a.confirmed) {
                        Spacer(Modifier.height(10.dp))
                        GhostButton("확정하기", small = true) { onConfirm(a.id) }
                    }
                }
            }
        }
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
    val refreshPerms = {
        notif = isNotificationAccessGranted(ctx)
        audio = hasMediaAudioPermission(ctx)
        batt = isBatteryUnrestricted(ctx)
        files = hasAllFilesAccess(ctx)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshPerms() }
    val battLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshPerms() }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshPerms() }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPerms() }
    val grantedCount = listOf(notif, audio, batt, files).count { it }

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
                Text("$grantedCount / 4 허용됨", style = JpText.meta, color = if (grantedCount == 4) c.sage else c.ink2)
            }
        }
        item {
            JpCard(Modifier.fillMaxWidth()) {
                Column {
                    SettingRow(Icons.Filled.Notifications, "알림 접근", "다른 앱 알림 캡처", {
                        notifLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { PermStat(notif) }
                    HLine()
                    SettingRow(Icons.Filled.Phone, "통화 녹음 폴더", "음성 파일 감시", {
                        audioLauncher.launch(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
                            else Manifest.permission.READ_EXTERNAL_STORAGE
                        )
                    }) { PermStat(audio) }
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
                    SettingRow(Icons.Filled.Refresh, "PC 연결", "동기화 사용 중 · 눌러서 새로고침", onRefresh) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(c.sage))
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
private fun MarkdownText(raw: String) {
    val c = JpTheme.colors
    val maxChars = 4_000
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
