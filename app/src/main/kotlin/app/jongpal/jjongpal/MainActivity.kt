package app.jongpal.jjongpal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JjongpalTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot(vm: AuthViewModel = hiltViewModel()) {
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
        )
    } else {
        LoginScreen(
            loading = state.loading,
            errorMessage = state.errorMessage,
            onLogin = { email, pw -> vm.login(email, pw) },
        )
    }
}
