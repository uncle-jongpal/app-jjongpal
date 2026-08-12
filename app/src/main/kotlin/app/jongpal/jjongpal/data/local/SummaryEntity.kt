package app.jongpal.jjongpal.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "summaries",
    indices = [
        Index("createdAt"),
        Index("viewed"),
    ],
)
data class SummaryEntity(
    @PrimaryKey val id: String,                     // 서버 UUID
    val eventId: String?,
    val userId: Int,
    val transcriptText: String?,               // 통화 원문(받아쓰기)
    val segmentsJson: String? = null,          // 문장별 시간 정보 [{s,e,t}] — "이 문장부터 듣기"용
    val audioPath: String? = null,             // 폰에 있는 녹음 파일 경로(있으면 재생 가능)
    val summary: String?,
    val rawJson: String,                            // {todos, appointments, people, action_items}
    val createdAt: Long,
    val viewed: Boolean = false,
    val pinned: Boolean = false,                    // 보관함에 담음 (사용자가 핀 꽂음)
)
