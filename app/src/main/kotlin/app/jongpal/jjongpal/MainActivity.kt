package app.jongpal.jjongpal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import app.jongpal.jjongpal.auth.AuthViewModel
import app.jongpal.jjongpal.auth.LoginScreen
import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.capture.call.CallSweepWorker
import app.jongpal.jjongpal.capture.call.FileObserverService
import app.jongpal.jjongpal.sync.SyncScheduler
import app.jongpal.jjongpal.ui.main.MainScreen
import app.jongpal.jjongpal.ui.theme.JjongpalTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var syncScheduler: SyncScheduler

    // 알림 탭으로 들어온 이동 요청. navTick 이 올라갈 때마다 화면이 반응한다.
    private val navSummaryId = mutableStateOf<String?>(null)
    private val navTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNavIntent(intent)
        setContent {
            JjongpalTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(navSummaryId = navSummaryId, navTick = navTick)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavIntent(intent)
    }

    /** 알림에서 온 인텐트면 이동 요청을 갱신한다. summary_id 가 있으면 그 통화 정리로. */
    private fun handleNavIntent(intent: Intent?) {
        if (intent == null) return
        // 런처 아이콘으로 그냥 실행된 경우엔 반응하지 않는다(알림에서 온 것만).
        if (!intent.hasExtra("nav")) return
        navSummaryId.value = intent.getStringExtra("summary_id")
        navTick.intValue += 1
    }
}

@Composable
private fun AppRoot(
    navSummaryId: MutableState<String?>,
    navTick: androidx.compose.runtime.MutableIntState,
    vm: AuthViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val syncScheduler = (context.applicationContext as JjongpalApp).appSyncScheduler

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) {
            // 로그인 직후: 캡처 서비스 시작 + 주기 동기화 등록 + 통화 녹음 일괄 검사 트리거
            FileObserverService.start(context)
            syncScheduler.schedulePeriodic()
            syncScheduler.scheduleNow()
            CallSweepWorker.runOnce(context)
        }
    }

    if (state.loggedIn) {
        MainScreen(
            onLogout = { vm.logout() },
            userName = state.userName,
            userId = state.userId ?: 0,
            openSummaryId = navSummaryId.value,
            navTick = navTick.intValue,
        )
    } else {
        LoginScreen(
            loading = state.loading,
            errorMessage = state.errorMessage,
            onLogin = { email, pw -> vm.login(email, pw) },
        )
    }
}
