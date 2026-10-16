package app.sunosongwriter.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-made prompt template (spec 5.8). Built-ins live in code, not here. */
@Entity(tableName = "prompt_template")
data class PromptTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [app.sunosongwriter.domain.prompt.SessionAction] name. */
    val action: String,
    val name: String,
    val body: String,
    /** Template key it was copied from, e.g. `builtin:review`. */
    val basedOn: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Which template an action uses by default: for everyone ([participantId] 0) or one participant (spec 5.8). */
@Entity(tableName = "template_default", primaryKeys = ["action", "participantId"])
data class TemplateDefaultEntity(
    val action: String,
    val participantId: Long,
    val templateKey: String,
)

/** A run of steps on one variant of a song. Manual mode uses one session per variant (spec 5.4). */
@Entity(
    tableName = "session",
    foreignKeys = [
        ForeignKey(SongEntity::class, ["id"], ["songId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(VariantEntity::class, ["id"], ["variantId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("songId"), Index("variantId")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val variantId: Long,
    /** `manual` now; `writers_room` from iteration 4. */
    val type: String,
    /** Send earlier messages as conversation history instead of only the current version (spec 5.17). */
    val fullTranscript: Boolean,
    val createdAt: Long,
    /** Writers' Room: JSON of [app.sunosongwriter.domain.workflow.RoomConfig]. */
    @ColumnInfo(defaultValue = "") val configJson: String = "",
    /** Writers' Room: JSON of [app.sunosongwriter.domain.workflow.RoomState]. */
    @ColumnInfo(defaultValue = "") val stateJson: String = "",
)

/** One step's prompt and reply. The transcript UI renders from these rows, so it survives process death. */
@Entity(
    tableName = "session_message",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class SessionMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val round: Int,
    /** [app.sunosongwriter.domain.prompt.SessionAction] name. */
    val action: String,
    val participantId: Long?,
    val participantName: String,
    val participantAvatar: String,
    /** The model that actually answered, e.g. "llama3.2:latest · Ollama" or "Claude app (relay)". */
    val modelLabel: String?,
    val templateKey: String,
    val instructions: String?,
    /** The exact prompt sent, or to be relayed. */
    val prompt: String,
    val content: String,
    /** [app.sunosongwriter.domain.workflow.MessageStatus] name. */
    val status: String,
    val error: String?,
    val basedOnVersionId: Long?,
    val producedVersionId: Long?,
    /** [app.sunosongwriter.domain.review.Verdict] name, for reviews. */
    val verdict: String?,
    val summary: String?,
    /** A review whose reply couldn't be read as JSON, even after one retry. */
    val parseFailed: Boolean,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Shown on finished steps, e.g. a prompt cut off by the model's context window. */
    val warning: String? = null,
    /** The variant a produced version goes to, when not the session's (Finalise → Framework-refined). */
    val targetVariantId: Long? = null,
    /** A style proposal: parsed but only saved as a version if the chair picks it (spec 5.5 step 2). */
    @ColumnInfo(defaultValue = "0") val proposal: Boolean = false,
    @ColumnInfo(defaultValue = "0") val chosen: Boolean = false,
    /** Cost reported by the provider (OpenRouter), in US dollars. */
    val costUsd: Double? = null,
    /** For a failed step: when its daily quota resets, if known. */
    val retryAtMillis: Long? = null,
)

@Entity(
    tableName = "suggestion",
    foreignKeys = [ForeignKey(SessionMessageEntity::class, ["id"], ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("messageId")],
)
data class SuggestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val participantName: String,
    /** [app.sunosongwriter.domain.review.SuggestionTarget] name. */
    val target: String,
    val section: String?,
    val change: String,
    val rationale: String?,
    /** [app.sunosongwriter.domain.review.Decision] name. */
    val decision: String,
    val editedChange: String?,
    val implementedInVersionId: Long?,
    val createdAt: Long,
)
