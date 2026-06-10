package app.jongpal.jjongpal.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "jjongpal_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) { prefs.edit().putString("access_token", value).apply() }

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) { prefs.edit().putString("refresh_token", value).apply() }

    var userId: Int
        get() = prefs.getInt("user_id", -1)
        set(value) { prefs.edit().putInt("user_id", value).apply() }

    var userName: String?
        get() = prefs.getString("user_name", null)
        set(value) { prefs.edit().putString("user_name", value).apply() }

    var userRole: String?
        get() = prefs.getString("user_role", null)
        set(value) { prefs.edit().putString("user_role", value).apply() }

    var deviceId: String?
        get() = prefs.getString("device_id", null)
        set(value) { prefs.edit().putString("device_id", value).apply() }

    // 어드민의 "모든 사용자 데이터 보기" 토글. 기본값: 꺼짐.
    // 일반 사용자는 RLS 가 자동으로 본인 데이터만 노출하므로 의미 없음.
    var showAllUsersForAdmin: Boolean
        get() = prefs.getBoolean("show_all_users", false)
        set(value) { prefs.edit().putBoolean("show_all_users", value).apply() }

    fun hasValidSession(): Boolean = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()

    fun clear() { prefs.edit().clear().apply() }
}
