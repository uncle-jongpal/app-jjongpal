package app.jongpal.jjongpal.ui.call

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jongpal.jjongpal.data.local.SummaryEntity
import org.json.JSONArray
import java.io.File

/** 받아쓰기 한 구간 — 시작 시각(초)과 말, 그리고 신뢰도 낮음 표시 */
data class Segment(val startSec: Double, val text: String, val lowConfidence: Boolean)

fun parseSegments(json: String?): List<Segment> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optString("t").trim()
            if (t.isEmpty()) null
            else Segment(o.optDouble("s", 0.0), t, o.optBoolean("low", false))
        }
    } catch (e: Exception) { emptyList() }
}

private fun mmss(sec: Double): String {
    val s = sec.toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}

/**
 * 통화 상세 — v2의 핵심 화면.
 *
 * · 요약과 받아쓰기를 탭으로 나눠 본다 (요약이 실패해도 받아쓰기는 읽힌다)
 * · 받아쓰기는 말풍선 형태
 * · **문장을 누르면 그 말이 나온 지점부터 녹음이 재생된다**
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailScreen(summary: SummaryEntity, onClose: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val segments = remember(summary.segmentsJson) { parseSegments(summary.segmentsJson) }
    val audioFile = remember(summary.audioPath) {
        summary.audioPath?.let { p -> File(p).takeIf { it.exists() } }
    }

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingFrom by remember { mutableStateOf<Double?>(null) }

    DisposableEffect(Unit) {
        onDispose { player?.release(); player = null }
    }

    fun playFrom(sec: Double) {
        val f = audioFile ?: return
        try {
            player?.release()
            val mp = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                seekTo((sec * 1000).toInt())
                start()
                setOnCompletionListener { playingFrom = null }
            }
            player = mp
            playingFrom = sec
        } catch (e: Exception) {
            playingFrom = null
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop() } ; it.release() }
        player = null
        playingFrom = null
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("통화 내용", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = { stop(); onClose() }) { Icon(Icons.Filled.Close, "닫기") }
            },
        )

        if (audioFile == null) {
            Text(
                "이 통화의 녹음 파일이 폰에 없어 재생은 안 돼요 (내용은 그대로 볼 수 있어요)",
                fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        } else if (playingFrom != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("▶ ${mmss(playingFrom!!)} 부터 재생 중", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { stop() }) { Text("정지") }
            }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("요약") })
            Tab(selected = tab == 1, onClick = { tab = 1 },
                text = { Text(if (segments.isNotEmpty()) "받아쓰기 (${segments.size})" else "받아쓰기") })
        }

        when (tab) {
            0 -> SummaryTab(summary)
            else -> TranscriptTab(
                segments = segments,
                fallbackText = summary.transcriptText,
                canPlay = audioFile != null,
                playingFrom = playingFrom,
                onPlay = { playFrom(it) },
            )
        }
    }
}

@Composable
private fun SummaryTab(s: SummaryEntity) {
    val text = s.summary
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            if (text.isNullOrBlank()) {
                Text(
                    "아직 요약이 없어요. 받아쓰기 탭에서 내용을 바로 볼 수 있어요.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // 목록 카드와 같은 마크다운 표시기를 써서 ###·- 가 그대로 보이지 않게 한다
                app.jongpal.jjongpal.ui.main.MarkdownText(text, maxChars = Int.MAX_VALUE)  // 상세에선 전문 표시
            }
        }
    }
}

@Composable
private fun TranscriptTab(
    segments: List<Segment>,
    fallbackText: String?,
    canPlay: Boolean,
    playingFrom: Double?,
    onPlay: (Double) -> Unit,
) {
    if (segments.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            item {
                Text(
                    fallbackText ?: "받아쓰기 내용이 아직 없어요.",
                    fontSize = 14.sp, lineHeight = 22.sp,
                )
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        items(segments) { seg ->
            val isPlaying = playingFrom != null && kotlin.math.abs(playingFrom - seg.startSec) < 0.01
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clickable(enabled = canPlay) { onPlay(seg.startSec) },
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    mmss(seg.startSec),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, end = 8.dp).width(38.dp),
                )
                Surface(
                    color = if (isPlaying) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            seg.text,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = if (seg.lowConfidence)
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (canPlay) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "이 부분부터 듣기",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = if (isPlaying) 1f else 0.45f),
                                modifier = Modifier.padding(start = 6.dp).size(18.dp),
                            )
                        }
                    }
                }
            }
            if (seg.lowConfidence) {
                Text(
                    "이 부분은 받아쓰기가 헛돌았을 수 있어요",
                    fontSize = 10.5.sp,
                    color = Color(0xFFB45309),
                    modifier = Modifier.padding(start = 46.dp, bottom = 2.dp),
                )
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}
