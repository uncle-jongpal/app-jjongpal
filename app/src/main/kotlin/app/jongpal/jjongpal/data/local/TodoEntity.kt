package app.jongpal.jjongpal.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "todos",
    indices = [
        Index("status"),
        Index("dueAt"),
        Index("syncStatus"),
    ],
)
data class TodoEntity(
    @PrimaryKey val id: String,                     // UUID 또는 서버 ID
    val userId: Int,
    val content: String,
    val source: String,                             // 'manual' | 'call' | 'notification' | 'ai_suggestion'
    val sourceEventId: String?,
    val sourceExcerpt: String? = null,              // 근거가 된 발화/알림 한 구절
    val dueAt: Long?,
    val relatedPerson: String?,
    val status: String = "open",                    // 'open' | 'done' | 'archived' | 'suggested' | 'dismissed' | 'parked'
    val priority: Float = 0f,                       // 비서가 매긴 중요도 0~1 (제안 정렬용)
    val completionConfidence: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val syncStatus: String = "PENDING",             // PC 와 동기화 상태
    val serverUpdatedAt: Long? = null,
)
