package app.sunosongwriter.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "backend")
data class BackendEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val presetId: String?,
    val name: String,
    val baseUrl: String,
    val modelsUrl: String?,
    /** [app.sunosongwriter.domain.model.AuthStyle] name. */
    val auth: String,
    /** [app.sunosongwriter.domain.model.FreeAccess] name. */
    val freeAccess: String,
    val isFreeTier: Boolean,
    val rpm: Int?,
    val rpd: Int?,
    val tpm: Int?,
    /** JSON list of [app.sunosongwriter.domain.model.ModelLimitRule]. */
    val modelLimitsJson: String,
    /** [app.sunosongwriter.domain.model.LimitScope] name. */
    val limitScope: String,
    val limitsFreeModelsOnly: Boolean,
    val dailyResetZone: String,
    val streamUsage: Boolean,
    val dailyRemainingHeader: Boolean,
    /** JSON object of header name to value. */
    val extraHeadersJson: String,
    /** Last characters of the API key, for masked display. The key itself lives in [app.sunosongwriter.data.secure.SecretStore]. */
    val keyHint: String?,
    val createdAt: Long,
    /** [app.sunosongwriter.domain.model.BackendKind] name. */
    @ColumnInfo(defaultValue = "OPENAI_COMPATIBLE") val kind: String = "OPENAI_COMPATIBLE",
    /** For relay backends: the chat app's package name. */
    val relayPackage: String? = null,
)

@Entity(
    tableName = "model_info",
    primaryKeys = ["backendId", "modelId"],
    foreignKeys = [
        ForeignKey(
            entity = BackendEntity::class,
            parentColumns = ["id"],
            childColumns = ["backendId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ModelInfoEntity(
    val backendId: Long,
    val modelId: String,
    val displayName: String,
    val pricedFree: Boolean?,
    val contextLength: Int?,
    val available: Boolean,
    val lastSeen: Long,
)

@Entity(
    tableName = "participant",
    foreignKeys = [
        ForeignKey(
            entity = BackendEntity::class,
            parentColumns = ["id"],
            childColumns = ["backendId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("backendId")],
)
data class ParticipantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val avatar: String,
    /** [app.sunosongwriter.domain.model.Role] name. */
    val defaultRole: String?,
    val backendId: Long?,
    val modelId: String?,
    /** JSON list of [app.sunosongwriter.domain.model.ModelRef]. */
    val fallbacksJson: String,
    val createdAt: Long,
)

/** Requests sent per backend, model and day, in the backend's reset time zone (spec 5.2). */
@Entity(
    tableName = "request_count",
    primaryKeys = ["backendId", "modelId", "day"],
    foreignKeys = [
        ForeignKey(
            entity = BackendEntity::class,
            parentColumns = ["id"],
            childColumns = ["backendId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RequestCountEntity(
    val backendId: Long,
    val modelId: String,
    /** ISO date, e.g. 2026-10-16. */
    val day: String,
    val count: Int,
)

/** One model request's tokens and cost (spec 5.17). No foreign keys: usage outlives songs and sessions. */
@Entity(tableName = "usage_record", indices = [Index("at"), Index("songId"), Index("sessionId")])
data class UsageRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val backendId: Long,
    /** Kept for display after the backend is deleted. */
    val backendName: String,
    val modelId: String,
    val songId: Long?,
    val sessionId: Long?,
    val messageId: Long?,
    val inputTokens: Int,
    val outputTokens: Int,
    val costUsd: Double?,
    /** [app.sunosongwriter.domain.usage.CostSource] name. */
    val costSource: String,
    val at: Long,
)

/** The editable price table, for estimating costs that providers don't report (spec 5.17). */
@Entity(tableName = "price_entry", primaryKeys = ["backendId", "modelId"])
data class PriceEntryEntity(
    val backendId: Long,
    val modelId: String,
    val inputPerMTok: Double,
    val outputPerMTok: Double,
    val updatedAt: Long,
)
