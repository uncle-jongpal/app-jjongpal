package app.jongpal.jjongpal.data.remote

import com.squareup.moshi.JsonClass
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

// 통화 음성 파일 업로드 — 별도 엔드포인트 (PostgREST 아님)
interface UploadApi {

    @Multipart
    @POST("upload/audio")
    suspend fun uploadAudio(
        @Part file: MultipartBody.Part,
        @Part("event_id") eventId: RequestBody,
        @Part("device_timestamp") deviceTimestamp: RequestBody? = null,
        @Part("device_id") deviceId: RequestBody? = null,
        @Part("duration_sec") durationSec: RequestBody? = null,
    ): Response<UploadAudioResponse>
}

@JsonClass(generateAdapter = true)
data class UploadAudioResponse(
    val ok: Boolean,
    val audio_file_id: String?,
    val size_bytes: Long?,
)
