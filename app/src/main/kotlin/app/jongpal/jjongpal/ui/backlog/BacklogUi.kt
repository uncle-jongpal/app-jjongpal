package app.jongpal.jjongpal.ui.backlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.jongpal.jjongpal.capture.call.BacklogCounts
import app.jongpal.jjongpal.capture.call.BacklogManager
import app.jongpal.jjongpal.capture.call.BacklogRange
import app.jongpal.jjongpal.ui.theme.JpText
import app.jongpal.jjongpal.ui.theme.JpTheme
import kotlinx.coroutines.launch

/**
 * 로그인 직후·오래 쉬었다 켠 뒤 뜨는 "밀린 통화 녹음" 확인 창.
 * 올릴 게 없으면 창 없이 조용히 넘어간다. "나중에"는 이번 실행만 닫음(다음에 열면 다시 물어봄).
 */
@Composable
fun BacklogPrompt(hold: Long, backlog: BacklogManager) {
    val c = JpTheme.colors
    val scope = rememberCoroutineScope()
    var counts by remember(hold) { mutableStateOf<BacklogCounts?>(null) }
    var picked by remember(hold) { mutableStateOf(BacklogRange.DAYS7) }
    var dismissed by remember(hold) { mutableStateOf(false) }
    var busy by remember(hold) { mutableStateOf(false) }

    LaunchedEffect(hold) {
        if (hold <= 0L) return@LaunchedEffect
        val n = backlog.counts()
        if (n.all == 0) backlog.choose(BacklogRange.DAYS7)  // 밀린 게 없으면 묻지 않고 기본값으로 정리
        else counts = n
    }

    val n = counts
    if (hold <= 0L || n == null || dismissed) return

    fun countOf(r: BacklogRange) = when (r) {
        BacklogRange.DAYS7 -> n.days7
        BacklogRange.DAYS30 -> n.days30
        BacklogRange.ALL -> n.all
        BacklogRange.NONE -> 0
    }

    AlertDialog(
        onDismissRequest = { if (!busy) dismissed = true },
        title = { Text("올리지 않은 통화 녹음이 있어요", style = JpText.card, color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "이 폰에 아직 올리지 않은 녹음 ${n.all}개가 있어요. 어디까지 올려서 정리할까요?",
                    style = JpText.body, color = c.ink2,
                )
                Spacer(Modifier.padding(top = 4.dp))
                BacklogRange.entries.forEach { r ->
                    val on = picked == r
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (on) c.accentSoft else c.surface)
                            .clickable(enabled = !busy) { picked = r }
                            .padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = on, onClick = { picked = r }, enabled = !busy,
                            colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.ink3),
                        )
                        Text(r.label, style = JpText.body, color = c.ink, modifier = Modifier.weight(1f))
                        if (r != BacklogRange.NONE) {
                            Spacer(Modifier.width(8.dp))
                            Text("${countOf(r)}개", style = JpText.meta, color = c.ink2)
                        }
                    }
                }
                Text(
                    "고르지 않은 녹음은 폰에만 남고 올라가지 않아요. 나중에 설정 > 지난 녹음 올리기에서 바꿀 수 있어요.",
                    style = JpText.meta, color = c.ink3, modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            Text(
                if (busy) "정리 중…" else if (picked == BacklogRange.NONE) "확인" else "${countOf(picked)}개 올리기",
                style = JpText.card, color = if (busy) c.ink3 else c.accent,
                modifier = Modifier.clickable(enabled = !busy) {
                    busy = true
                    scope.launch { backlog.choose(picked) }
                }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        },
        dismissButton = {
            if (!busy) {
                Text(
                    "나중에", style = JpText.card, color = c.ink2,
                    modifier = Modifier.clickable { dismissed = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        },
        containerColor = c.surface,
    )
}
