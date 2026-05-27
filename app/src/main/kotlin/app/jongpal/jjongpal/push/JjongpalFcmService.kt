package app.jongpal.jjongpal.push

import app.jongpal.jjongpal.auth.TokenManager
import app.jongpal.jjongpal.data.remote.DevicePatch
import app.jongpal.jjongpal.data.remote.PcApi
import app.jongpal.jjongpal.sync.SyncScheduler
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class JjongpalFcmService : FirebaseMessagingService() {

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var pcApi: PcApi
    @Inject lateinit var syncScheduler: SyncScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("new FCM token")
        val deviceId = tokenManager.deviceId ?: return
        if (!tokenManager.hasValidSession()) return
        scope.launch {
            try {
                pcApi.patchDevice("eq.$deviceId", DevicePatch(fcm_token = token))
                Timber.i("FCM token uploaded for device %s", deviceId)
            } catch (e: Exception) {
                Timber.e(e, "FCM token upload error")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val type = message.data["type"]
        Timber.d("FCM message received type=%s", type)
        if (!tokenManager.hasValidSession()) return
        // 푸시는 트리거만. 본문은 폰이 PC 에서 다시 가져옴.
        syncScheduler.scheduleNow()
    }
}
