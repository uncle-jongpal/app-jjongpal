package app.jongpal.jjongpal.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.jongpal.jjongpal.BuildConfig
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 서버 `/static/latest.json` — 새 APK 를 올릴 때 같이 고친다(scripts/publish-apk.sh). */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apk: String,            // /static 아래 파일 이름
    val sha256: String? = null, // 받은 파일 검증용(소문자 16진수)
    val notes: String? = null,  // 바뀐 점 한두 줄
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * 앱 안 업데이트: 서버의 최신 버전 정보를 보고 → APK 를 앱 캐시에 받고 → 안드로이드 설치 화면을 연다.
 * 설치 자체는 시스템이 사용자 확인을 받아 진행한다(같은 서명이라 덮어쓰기, 데이터 유지).
 * /static 은 로그인 없이 열리는 경로라 인증 없는 별도 통신을 쓴다.
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val ctx: Context,
    moshi: Moshi,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val adapter = moshi.adapter(UpdateInfo::class.java)
    private val base = BuildConfig.PC_BASE_URL.trimEnd('/')
    private val dir get() = File(ctx.cacheDir, "updates")

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun check() = withContext(Dispatchers.IO) {
        if (_state.value is UpdateState.Downloading) return@withContext
        _state.value = UpdateState.Checking
        _state.value = try {
            val req = Request.Builder()
                .url("$base/static/latest.json?t=${System.currentTimeMillis()}")
                .header("Cache-Control", "no-cache")
                .build()
            val info = client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                adapter.fromJson(res.body!!.string()) ?: throw IllegalStateException("빈 응답")
            }
            if (info.versionCode > BuildConfig.VERSION_CODE) {
                // 이미 받아 둔 같은 버전 파일이 있으면 바로 설치 단계로
                val f = File(dir, info.apk)
                if (f.exists() && (info.sha256 == null || sha256(f) == info.sha256.lowercase())) UpdateState.Ready(info, f)
                else UpdateState.Available(info)
            } else {
                dir.deleteRecursively()  // 설치 끝난 옛 파일 정리
                UpdateState.UpToDate
            }
        } catch (e: Exception) {
            Timber.w(e, "업데이트 확인 실패")
            UpdateState.Failed("업데이트 확인에 실패했어요. 잠시 후 다시 시도해주세요")
        }
    }

    suspend fun download(info: UpdateInfo) = withContext(Dispatchers.IO) {
        if (_state.value is UpdateState.Downloading) return@withContext
        _state.value = UpdateState.Downloading(info, 0)
        _state.value = try {
            dir.deleteRecursively()
            dir.mkdirs()
            val part = File(dir, info.apk + ".part")
            val req = Request.Builder().url("$base/static/${info.apk}").build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                val body = res.body!!
                val total = body.contentLength()
                var read = 0L
                var shown = -1
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            val pct = if (total > 0) (read * 100 / total).toInt() else 0
                            if (pct != shown) { shown = pct; _state.value = UpdateState.Downloading(info, pct) }
                        }
                    }
                }
            }
            if (info.sha256 != null && sha256(part) != info.sha256.lowercase()) {
                part.delete()
                throw IllegalStateException("파일 검증 실패")
            }
            val f = File(dir, info.apk)
            part.renameTo(f)
            UpdateState.Ready(info, f)
        } catch (e: Exception) {
            Timber.w(e, "업데이트 받기 실패")
            UpdateState.Failed("새 버전을 받지 못했어요. 와이파이·데이터 연결을 확인하고 다시 시도해주세요")
        }
    }

    /** 이 앱이 다른 앱(=새 버전) 설치를 요청할 수 있는지 — 처음 한 번은 사용자가 허용해야 한다. */
    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
