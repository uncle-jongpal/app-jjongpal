package app.jongpal.jjongpal.auth

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: AuthApi,
    private val tokenManager: TokenManager,
) {
    suspend fun login(email: String, password: String, fcmToken: String? = null): Result<UserPublic> {
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".take(120)
        return try {
            val resp = api.login(LoginRequest(email.trim(), password, deviceName, fcmToken))
            if (!resp.isSuccessful) {
                Result.failure(Exception("login failed: ${resp.code()}"))
            } else {
                val body = resp.body() ?: return Result.failure(Exception("empty body"))
                tokenManager.accessToken = body.access_token
                tokenManager.refreshToken = body.refresh_token
                tokenManager.userId = body.user.id
                tokenManager.userName = body.user.name
                tokenManager.userRole = body.user.role
                tokenManager.deviceId = body.device_id
                Result.success(body.user)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout(): Result<Unit> {
        val rt = tokenManager.refreshToken
        return try {
            if (!rt.isNullOrBlank()) api.logout(LogoutRequest(rt))
            tokenManager.clear()
            Result.success(Unit)
        } catch (e: Exception) {
            tokenManager.clear()
            Result.failure(e)
        }
    }
}
