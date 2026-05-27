package app.jongpal.jjongpal.ui.permission

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.jongpal.jjongpal.capture.notification.NotificationCaptureService

@Composable
fun PermissionScreen() {
    val context = LocalContext.current
    var notifAccess by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    var battery by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    var postNotif by remember { mutableStateOf(hasPostNotificationsPermission(context)) }
    var mediaAudio by remember { mutableStateOf(hasMediaAudioPermission(context)) }
    var allFiles by remember { mutableStateOf(hasAllFilesAccess(context)) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        notifAccess = isNotificationAccessGranted(context)
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        battery = isBatteryUnrestricted(context)
    }
    val postNotifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        postNotif = hasPostNotificationsPermission(context)
    }
    val mediaAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        mediaAudio = hasMediaAudioPermission(context)
    }
    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allFiles = hasAllFilesAccess(context)
    }

    LaunchedEffect(Unit) {
        // 화면 들어올 때마다 재확인 (외부 설정에서 켰을 수 있음)
        notifAccess = isNotificationAccessGranted(context)
        battery = isBatteryUnrestricted(context)
        postNotif = hasPostNotificationsPermission(context)
        mediaAudio = hasMediaAudioPermission(context)
        allFiles = hasAllFilesAccess(context)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("권한 설정", style = MaterialTheme.typography.titleLarge)
        Text("앱이 동작하려면 아래 권한이 필요해요.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))

        PermissionRow(
            label = "알림 접근 허용",
            description = "다른 앱의 알림을 캡처하려면 필요합니다.",
            granted = notifAccess,
            onClick = { notifLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        )
        PermissionRow(
            label = "배터리 최적화 제외",
            description = "백그라운드에서 안정적으로 동작하기 위해 필요합니다.",
            granted = battery,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    batteryLauncher.launch(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                    )
                }
            },
        )
        PermissionRow(
            label = "알림 표시 권한",
            description = "안드로이드 13 이후 — 본 앱이 알림을 띄울 수 있도록.",
            granted = postNotif,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        PermissionRow(
            label = "음성 파일 접근",
            description = "통화 녹음 음성 파일 (m4a / mp3) 을 읽기 위해 필요합니다.",
            granted = mediaAudio,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaAudioLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                } else {
                    mediaAudioLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            },
        )
        PermissionRow(
            label = "모든 파일 접근 (통화 녹음 폴더)",
            description = "갤럭시 통화 녹음 폴더 (Recordings/Call) 를 감시하려면 이 권한이 필요합니다. 설정 화면에서 직접 켜 주세요.",
            granted = allFiles,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:" + context.packageName)
                    }
                    try {
                        allFilesLauncher.launch(intent)
                    } catch (_: Exception) {
                        // 일부 기기는 ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION 로 전체 화면만 뜸
                        allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            },
        )
    }
}

@Composable
private fun PermissionRow(label: String, description: String, granted: Boolean, onClick: () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(description, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text(if (granted) "허용됨" else "허용 안 됨", style = MaterialTheme.typography.bodySmall)
        Button(onClick = onClick, enabled = !granted) {
            Text(if (granted) "완료" else "설정 열기")
        }
    }
}

private fun isNotificationAccessGranted(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    val cn = ComponentName(context, NotificationCaptureService::class.java).flattenToString()
    val cnShort = ComponentName(context, NotificationCaptureService::class.java).flattenToShortString()
    return flat.contains(cn) || flat.contains(cnShort)
}

private fun isBatteryUnrestricted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun hasPostNotificationsPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return nm.areNotificationsEnabled()
}

private fun hasMediaAudioPermission(context: Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_AUDIO
    else
        Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}

private fun hasAllFilesAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        // 안드로이드 10 이하는 일반 READ_EXTERNAL_STORAGE 로 충분
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}
