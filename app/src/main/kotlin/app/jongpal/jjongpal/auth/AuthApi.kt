package app.jongpal.jjongpal.auth

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApi {
    @POST("/auth/login")
    suspend fun login(@Body req: LoginRequest): Response<LoginResponse>

    @POST("/auth/refresh")
    suspend fun refresh(@Body req: RefreshRequest): Response<RefreshResponse>

    @POST("/auth/logout")
    suspend fun logout(@Body req: LogoutRequest): Response<Unit>
}

@JsonClass(generateAdapter = true)
data class LoginRequest(
    val email: String,
    val password: String,
    val device_name: String,
    val fcm_token: String? = null,
)

@JsonClass(generateAdapter = true)
data class UserPublic(
    val id: Int,
    val name: String,
    val email: String,
    val role: String,
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    val access_token: String,
    val refresh_token: String,
    val user: UserPublic,
    val device_id: String,
)

@JsonClass(generateAdapter = true)
data class RefreshRequest(val refresh_token: String)

@JsonClass(generateAdapter = true)
data class RefreshResponse(val access_token: String)

@JsonClass(generateAdapter = true)
data class LogoutRequest(val refresh_token: String)
