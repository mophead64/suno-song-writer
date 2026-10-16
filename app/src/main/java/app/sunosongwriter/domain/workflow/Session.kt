package app.sunosongwriter.domain.workflow

import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.domain.prompt.TemplateKey
import app.sunosongwriter.domain.review.Verdict

enum class MessageStatus(val label: String) {
    PENDING("Queued"),
    STREAMING("Writing…"),
    RATE_LIMITED("Rate limited"),
    WAITING_RELAY("Waiting for you to relay"),
    DONE("Done"),
    FAILED("Failed"),
    SKIPPED("Skipped"),
    ;

    /** Finished one way or another: done, or skipped by the chair. */
    val isSettled: Boolean get() = this == DONE || this == SKIPPED

    val isActive: Boolean get() = this == PENDING || this == STREAMING || this == RATE_LIMITED
}

data class Session(
    val id: Long,
    val songId: Long,
    val variantId: Long,
    val fullTranscript: Boolean,
    val type: String = "manual",
    val configJson: String = "",
    val stateJson: String = "",
    val createdAt: Long = 0,
)

data class SessionMessage(
    val id: Long,
    val sessionId: Long,
    val round: Int,
    val action: SessionAction,
    val participantId: Long?,
    val participantName: String,
    val participantAvatar: String,
    val modelLabel: String?,
    val templateKey: TemplateKey,
    val instructions: String?,
    val prompt: String,
    val content: String,
    val status: MessageStatus,
    val error: String?,
    val basedOnVersionId: Long?,
    val producedVersionId: Long?,
    val verdict: Verdict?,
    val summary: String?,
    val parseFailed: Boolean,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val createdAt: Long,
    val warning: String? = null,
    val targetVariantId: Long? = null,
    val proposal: Boolean = false,
    val chosen: Boolean = false,
    val costUsd: Double? = null,
    val retryAtMillis: Long? = null,
)

/** A relay step waiting for its reply, with the song it belongs to (for the share sheet). */
data class WaitingRelay(val message: SessionMessage, val songId: Long, val inRoom: Boolean = false)
