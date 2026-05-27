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
    val dueAt: Long?,
    val relatedPerson: String?,
    val status: String = "open",                    // 'open' | 'done' | 'archived'
    val completionConfidence: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val syncStatus: String = "PENDING",             // PC 와 동기화 상태
    val serverUpdatedAt: Long? = null,
)
