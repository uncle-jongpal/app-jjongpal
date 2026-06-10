package app.jongpal.jjongpal.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "appointments",
    indices = [
        Index("startAt"),
    ],
)
data class AppointmentEntity(
    @PrimaryKey val id: String,
    val userId: Int,
    val sourceEventId: String?,
    val sourceExcerpt: String? = null,
    val title: String,
    val startAt: Long,
    val endAt: Long?,
    val location: String?,
    val withPerson: String?,
    val confidence: Float,
    val confirmed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
