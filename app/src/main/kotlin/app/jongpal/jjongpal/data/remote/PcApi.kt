package app.jongpal.jjongpal.data.remote

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
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

    // 이벤트 메타데이터 부분 갱신 — 통화 업로드 후 상대 전화번호를 metadata_json 에 채워 넣을 때 사용.
    // (통화 event 는 서버가 업로드로 생성하고 metadata_json 이 비어 있어, 본인 권한으로 PATCH 해 번호를 넣음)
    @PATCH("rest/events")
    suspend fun patchEvent(
        @Query("id") idEq: String,           // 예: "eq.<id>"
        @Body patch: EventMetaPatch,
    ): Response<Unit>

    // 통화 이벤트의 (id, metadata_json) 만 가볍게 받아 번호 검색용 맵 구성.
    @GET("rest/events")
    suspend fun listCallEventMeta(
        @Query("user_id") userIdEq: String? = null,
        @Query("type") typeEq: String = "eq.call",
        @Query("select") select: String = "id,metadata_json",
        @Query("limit") limit: Int = 5000,
    ): Response<List<EventMetaRow>>

    // 할 일 목록 가져오기.
    // userIdEq: "eq.<본인 id>" 박아서 본인 데이터만 받음 (기본).
    //          어드민의 "모든 사용자 보기" 모드에서 null 로 호출하면 RLS 의 admin 권한으로 전체 보임.
    @GET("rest/todos")
    suspend fun listTodos(
        @Query("user_id") userIdEq: String? = null,
        @Query("order") order: String = "updated_at.desc",
        @Query("limit") limit: Int = 2000,   // 200 이면 그 이상 옛 항목이 동기화 안 됨(스테일 카운트). 전량 받도록 상향.
    ): Response<List<TodoDto>>

    // 할 일 업서트 (id 충돌 시 갱신).
    // PostgREST 는 POST 가 기본 INSERT 라, 이미 있는 id 면 409. resolution=merge-duplicates 헤더로 upsert 동작.
    @Headers("Prefer: resolution=merge-duplicates")
    @POST("rest/todos")
    suspend fun upsertTodos(@Body todos: List<TodoDto>): Response<Unit>

    // 할 일 부분 갱신 (상태 변경 등)
    @PATCH("rest/todos")
    suspend fun patchTodo(
        @Query("id") idEq: String,           // 예: "eq.<id>"
        @Body patch: TodoPatch,
    ): Response<Unit>

    // 요약 목록 (PC 가 클로드 코드로 정리한 결과)
    // userIdEq: "eq.<본인 id>" 박아서 본인 데이터만. 어드민 모든 사용자 보기는 null.
    @GET("rest/summaries")
    suspend fun listSummaries(
        @Query("user_id") userIdEq: String? = null,
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 2000,
    ): Response<List<SummaryDto>>

    // 통화 원문(받아쓰기) — 문장별 시간 정보 포함. "이 문장부터 듣기" 기능용.
    @GET("rest/transcripts")
    suspend fun listTranscripts(
        @Query("select") select: String = "id,event_id,audio_file_id,text,segments_json,created_at",
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 2000,
    ): Response<List<TranscriptDto>>

    // 통화 정리 부분 갱신 (보관함 핀 등)
    @PATCH("rest/summaries")
    suspend fun patchSummary(
        @Query("id") idEq: String,           // 예: "eq.<id>"
        @Body patch: SummaryPatch,
    ): Response<Unit>

    // 약속 목록
    // userIdEq: "eq.<본인 id>" 박아서 본인 데이터만. 어드민 모든 사용자 보기는 null.
    @GET("rest/appointments")
    suspend fun listAppointments(
        @Query("user_id") userIdEq: String? = null,
        @Query("order") order: String = "start_at.asc",
        @Query("start_at") startAtFilter: String? = null,    // 예: "gte.2026-05-20T00:00:00Z"
        @Query("limit") limit: Int = 2000,
    ): Response<List<AppointmentDto>>

    // 약속 부분 갱신 (확정 등)
    @PATCH("rest/appointments")
    suspend fun patchAppointment(
        @Query("id") idEq: String,           // 예: "eq.<id>"
        @Body patch: AppointmentPatch,
    ): Response<Unit>

    // 약속 삭제 (오인식된 약속 치우기)
    @DELETE("rest/appointments")
    suspend fun deleteAppointment(
        @Query("id") idEq: String,           // 예: "eq.<id>"
    ): Response<Unit>

    // 디바이스 정보 갱신 (FCM 토큰 등)
    @PATCH("rest/devices")
    suspend fun patchDevice(
        @Query("id") idEq: String,
        @Body patch: DevicePatch,
    ): Response<Unit>

    // 처리 실패 목록 (받아쓰기 / 정리 / 알림 분류 실패).
    // failed_items 뷰가 RLS 로 본인 데이터만 노출.
    @GET("rest/failed_items")
    suspend fun listFailed(
        @Query("order") order: String = "occurred_at.desc",
        @Query("limit") limit: Int = 500,
        @Query("stage") stage: String = "neq.event",   // v2: 알림(event) 실패 제외, 통화만
    ): Response<List<FailedItemDto>>

    // 수동 재시도 — 해당 건의 FAILED 상태를 PENDING 으로 되돌림 (PROCESSING 은 안 건드림).
    // 되돌린 행이 있으면 (stage, reset_count) 행을 반환. 되돌릴 게 없으면 빈 배열 → 실제 재시도 아님.
    @POST("rest/rpc/retry_failed_item")
    suspend fun retryFailedItem(@Body body: RetryReq): Response<List<RetryResult>>

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

// 통화 이벤트 metadata_json 에 번호를 넣기 위한 PATCH 바디
@JsonClass(generateAdapter = true)
data class EventMetaPatch(
    val metadata_json: Map<String, Any?>?,
)

// 번호 검색용 경량 행 (id + metadata_json)
@JsonClass(generateAdapter = true)
data class EventMetaRow(
    val id: String,
    val metadata_json: Map<String, Any?>? = null,
)

@JsonClass(generateAdapter = true)
data class TodoDto(
    val id: String,
    val user_id: Int,
    val content: String,
    val source: String?,
    val source_event_id: String?,
    val source_excerpt: String? = null,
    val due_at: String?,
    val related_person: String?,
    val status: String,
    val priority: Float = 0f,
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
    val pinned: Boolean = false,
    val created_at: String,
)

@JsonClass(generateAdapter = true)
data class TranscriptDto(
    val id: String,
    val event_id: String?,
    val audio_file_id: String?,
    val text: String?,
    val segments_json: List<Map<String, Any?>>? = null,
    val created_at: String,
)

@JsonClass(generateAdapter = true)
data class SummaryPatch(
    val pinned: Boolean? = null,
)

@JsonClass(generateAdapter = true)
data class AppointmentDto(
    val id: String,
    val user_id: Int,
    val source_event_id: String?,
    val source_excerpt: String? = null,
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
data class AppointmentPatch(
    val confirmed: Boolean? = null,
)

@JsonClass(generateAdapter = true)
data class FailedItemDto(
    val event_id: String,
    val type: String,
    val stage: String,                // 'transcript' | 'summary' | 'event'
    val error_message: String? = null,
    val label: String? = null,
    val occurred_at: String? = null,
)

@JsonClass(generateAdapter = true)
data class RetryReq(
    val p_event_id: String,
)

@JsonClass(generateAdapter = true)
data class RetryResult(
    val stage: String,
    val reset_count: Int = 0,
)

@JsonClass(generateAdapter = true)
data class DevicePatch(
    val fcm_token: String? = null,
    val last_seen: String? = null,
)
