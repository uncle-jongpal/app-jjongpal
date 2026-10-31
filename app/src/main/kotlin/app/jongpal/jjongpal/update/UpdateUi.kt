package app.jongpal.jjongpal.update

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.jongpal.jjongpal.ui.theme.JpText
import app.jongpal.jjongpal.ui.theme.JpTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/** 화면에서 쓰는 업데이트 동작 묶음 — 메인 화면의 안내 창과 설정 화면의 "앱 버전" 줄이 같이 쓴다. */
class UpdateController(
    val updater: AppUpdater,
    private val scope: CoroutineScope,
    private val ctx: Context,
    private val askPermission: (File) -> Unit,
) {
    /** 사용자가 "업데이트"를 눌렀으면 받기가 끝나는 즉시 설치 화면을 연다. */
    var installWhenReady by mutableStateOf(false)

    fun check() { scope.launch { updater.check() } }

    fun update(info: UpdateInfo) {
        installWhenReady = true
        scope.launch { updater.download(info) }
    }

    fun install(file: File) {
        installWhenReady = false
        if (updater.canInstall()) start(file) else askPermission(file)
    }

    private fun start(file: File) {
        try {
            ctx.startActivity(updater.installIntent(file))
        } catch (e: Exception) {
            Timber.w(e, "설치 화면 열기 실패")
        }
    }

    /** 설정 화면 "앱 버전" 줄을 눌렀을 때 — 상태에 맞는 다음 단계. */
    fun onTap(state: UpdateState) = when (state) {
        is UpdateState.Available -> update(state.info)
        is UpdateState.Ready -> install(state.file)
        is UpdateState.Checking, is UpdateState.Downloading -> Unit
        else -> check()
    }
}

@Composable
fun rememberUpdateController(updater: AppUpdater): UpdateController {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<File?>(null) }
    // "이 출처의 앱 설치 허용" 화면에서 돌아오면, 허용했을 때만 이어서 설치
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val f = pending
        pending = null
        if (f != null && updater.canInstall()) {
            try { ctx.startActivity(updater.installIntent(f)) } catch (e: Exception) { Timber.w(e, "설치 화면 열기 실패") }
        }
    }
    return remember(updater) {
        UpdateController(updater, scope, ctx) { file ->
            pending = file
            permLauncher.launch(updater.installPermissionIntent())
        }
    }
}

/** 설정 화면 한 줄 설명. */
fun UpdateState.describe(current: String): String = when (this) {
    UpdateState.Idle -> "현재 $current · 눌러서 업데이트 확인"
    UpdateState.Checking -> "현재 $current · 확인 중…"
    UpdateState.UpToDate -> "현재 $current · 최신 버전이에요"
    is UpdateState.Available -> "새 버전 ${info.versionName} 있음 · 눌러서 업데이트"
    is UpdateState.Downloading -> "새 버전 ${info.versionName} 받는 중 $percent%"
    is UpdateState.Ready -> "새 버전 ${info.versionName} 준비됨 · 눌러서 설치"
    is UpdateState.Failed -> "현재 $current · $message"
}

/** 앱을 열었을 때 새 버전이 있으면 띄우는 안내 창. "나중에"를 누르면 이번 실행 동안은 안 띄운다. */
@Composable
fun UpdatePrompt(state: UpdateState, ctl: UpdateController) {
    val c = JpTheme.colors
    var dismissed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (ctl.installWhenReady && state is UpdateState.Ready) ctl.install(state.file)
    }

    if (dismissed) return
    val info = when (state) {
        is UpdateState.Available -> state.info
        is UpdateState.Downloading -> state.info
        is UpdateState.Ready -> state.info
        else -> return
    }
    val downloading = state is UpdateState.Downloading

    AlertDialog(
        onDismissRequest = { if (!downloading) dismissed = true },
        title = { Text("새 버전 ${info.versionName}", style = JpText.card, color = c.ink) },
        text = {
            Column {
                Text(
                    info.notes?.takeIf { it.isNotBlank() } ?: "새 버전이 나왔어요. 지금 업데이트할까요?",
                    style = JpText.body, color = c.ink2,
                )
                if (state is UpdateState.Downloading) {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        color = c.accent, trackColor = c.surface2,
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    )
                    Text("받는 중 ${state.percent}%", style = JpText.meta, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
                } else {
                    Text(
                        "설치 화면이 뜨면 \"업데이트\"를 눌러 주세요. 기록과 로그인은 그대로 남아요.",
                        style = JpText.meta, color = c.ink3, modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (!downloading) {
                Text(
                    if (state is UpdateState.Ready) "설치" else "업데이트",
                    style = JpText.card, color = c.accent,
                    modifier = Modifier.clickable { ctl.onTap(state) }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        },
        dismissButton = {
            if (!downloading) {
                Text(
                    "나중에",
                    style = JpText.card, color = c.ink2,
                    modifier = Modifier.clickable { dismissed = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        },
        containerColor = c.surface,
    )
}
