package app.jongpal.jjongpal.auth

import app.jongpal.jjongpal.BuildConfig
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
    moshi: Moshi,
) : Interceptor {

    // refresh 호출 전용 클라이언트 — 본 인터셉터를 거치지 않게 별도 인스턴스. 한 번만 만들어 재사용.
    private val refreshClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val refreshReqAdapter: JsonAdapter<RefreshRequest> = moshi.adapter(RefreshRequest::class.java)
    private val refreshRespAdapter: JsonAdapter<RefreshResponse> = moshi.adapter(RefreshResponse::class.java)

    // refresh 동시성 가드 — 같은 시점에 여러 요청이 401 받았을 때 한 번만 refresh
    private val refreshLock = Any()
    @Volatile private var lastRefreshAccessToken: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // 로그인 / 재발급 자체엔 인증 헤더 붙이지 않음
        val path = original.url.encodedPath
        if (path.startsWith("/auth/login") || path.startsWith("/auth/refresh")) {
            return chain.proceed(original)
        }

        val tokenBefore = tokenManager.accessToken
        val first = chain.proceed(original.withAuth(tokenBefore))
        if (first.code != 401) return first

        // 401 → refresh 시도
        first.close()

        val refreshed = synchronized(refreshLock) {
            // 다른 스레드가 refresh 해서 새 토큰이 있으면 그걸 사용
            val current = tokenManager.accessToken
            if (current != null && current != tokenBefore) {
                true
            } else {
                tryRefresh()
            }
        }

        if (!refreshed) {
            // refresh 실패 → 토큰 비웠음. 원본 호출 (인증 없이) 반환. UI 측에서 로그아웃 처리.
            return chain.proceed(original.withAuth(null))
        }

        return chain.proceed(original.withAuth(tokenManager.accessToken))
    }

    private fun Request.withAuth(token: String?): Request =
        if (token.isNullOrBlank()) newBuilder().removeHeader("Authorization").build()
        else newBuilder().header("Authorization", "Bearer $token").build()

    private fun tryRefresh(): Boolean {
        val rt = tokenManager.refreshToken ?: return false
        val baseUrl = BuildConfig.PC_BASE_URL.trimEnd('/')
        return try {
            val body = refreshReqAdapter.toJson(RefreshRequest(rt))
            val req = Request.Builder()
                .url("$baseUrl/auth/refresh")
                .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            refreshClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Timber.w("refresh failed status=${r.code}")
                    if (r.code == 401) tokenManager.clear()
                    return false
                }
                val rb = r.body?.string() ?: return false
                val parsed = refreshRespAdapter.fromJson(rb) ?: return false
                tokenManager.accessToken = parsed.access_token
                lastRefreshAccessToken = parsed.access_token
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "refresh error")
            false
        }
    }
}
