package app.jongpal.jjongpal.data.remote

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// PostgREST 가 디비 테이블을 자동 REST 엔드포인트로 노출.
// `/rest/<table>` 으로 호출. 사용자별 권한 (RLS) 가 자동 필터링.

interface PcApi {

    // 이벤트 (알림 / 통화) 업로드
    @POST("rest/events")
    suspend fun postEvents(@Body events: List<EventDto>): Response<Unit>

    // 할 일 목록 가져오기
    @GET("rest/todos")
    suspend fun listTodos(
        @Query("order") order: String = "updated_at.desc",
        @Query("limit") limit: Int = 200,
    ): Response<List<TodoDto>>

    // 할 일 업서트 (id 충돌 시 갱신)
    @POST("rest/todos")
    suspend fun upsertTodos(@Body todos: List<TodoDto>): Response<Unit>

    // 할 일 부분 갱신 (상태 변경 등)
    @PATCH("rest/todos")
    suspend fun patchTodo(
        @Query("id") idEq: String,           // 예: "eq.<id>"
        @Body patch: TodoPatch,
    ): Response<Unit>

    // 요약 목록 (PC 가 클로드 코드로 정리한 결과)
    @GET("rest/summaries")
    suspend fun listSummaries(
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 100,
    ): Response<List<SummaryDto>>

    // 약속 목록
    @GET("rest/appointments")
    suspend fun listAppointments(
        @Query("order") order: String = "start_at.asc",
        @Query("start_at") startAtFilter: String? = null,    // 예: "gte.2026-05-20T00:00:00Z"
        @Query("limit") limit: Int = 100,
    ): Response<List<AppointmentDto>>

    // 디바이스 정보 갱신 (FCM 토큰 등)
    @PATCH("rest/devices")
    suspend fun patchDevice(
        @Query("id") idEq: String,
        @Body patch: DevicePatch,
    ): Response<Unit>

    // 통화 음성 파일 업로드는 별도 엔드포인트
    // (multipart 라 PostgREST 가 아닌 upload-receiver 가 처리)
}

@JsonClass(generateAdapter = true)
data class EventDto(
    val id: String,
    val user_id: Int,
    val device_id: String?,
    val type: String,
    val source_package: String?,
    val title: String?,
    val content: String?,
    val timestamp: String,            // ISO 8601 (서버에서 다시 받지만 INSERT 용도)
    val device_timestamp: String?,
    val metadata_json: Map<String, Any?>? = null,
)

@JsonClass(generateAdapter = true)
data class TodoDto(
    val id: String,
    val user_id: Int,
    val content: String,
    val source: String?,
    val source_event_id: String?,
    val due_at: String?,
    val related_person: String?,
    val status: String,
    val completion_confidence: Float = 0f,
    val created_at: String,
    val updated_at: String,
    val completed_at: String?,
)

@JsonClass(generateAdapter = true)
data class TodoPatch(
    val status: String? = null,
    val content: String? = null,
    val due_at: String? = null,
    val completed_at: String? = null,
    val updated_at: String? = null,
)

@JsonClass(generateAdapter = true)
data class SummaryDto(
    val id: String,
    val transcript_id: String?,
    val event_id: String?,
    val user_id: Int,
    val summary: String?,
    val raw_json: Map<String, Any?>?,
    val pushed: Boolean,
    val pushed_at: String?,
    val created_at: String,
)

@JsonClass(generateAdapter = true)
data class AppointmentDto(
    val id: String,
    val user_id: Int,
    val source_event_id: String?,
    val title: String,
    val start_at: String,
    val end_at: String?,
    val location: String?,
    val with_person: String?,
    val confidence: Float,
    val confirmed: Boolean,
    val created_at: String,
)

@JsonClass(generateAdapter = true)
data class DevicePatch(
    val fcm_token: String? = null,
    val last_seen: String? = null,
)
