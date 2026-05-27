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
    val transcriptText: String?,
    val summary: String?,
    val rawJson: String,                            // {todos, appointments, people, action_items}
    val createdAt: Long,
    val viewed: Boolean = false,
)
