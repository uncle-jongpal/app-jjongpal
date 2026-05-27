package app.jongpal.jjongpal.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    indices = [
        Index("syncStatus"),
        Index("type"),
        Index("timestamp"),
        Index("phoneClassification"),
    ],
)
data class EventEntity(
    @PrimaryKey val id: String,             // UUID
    val type: String,                       // 'notification' | 'call'
    val sourcePackage: String?,
    val title: String?,
    val content: String?,
    val sender: String?,                    // 알림의 발신자 등
    val timestamp: Long,                    // 폰 시각 (밀리초)
    val syncStatus: String = "PENDING",     // 'PENDING' | 'SYNCING' | 'SYNCED' | 'FAILED'
    val syncedAt: Long? = null,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val metadataJson: String? = null,       // 추가 메타 (통화 파일 경로 등)
    // 폰 측 사전 분류 — 'send' (PC 로 보냄) / 'skip_noise' (광고·시스템) / 'skip_sensitive' (OTP·금융) / 'pending' (분류 안 됨)
    val phoneClassification: String = "send",
    val phoneClassificationReason: String? = null,
)
