package app.sunosongwriter.data.repo

import app.sunosongwriter.data.db.SessionDao
import app.sunosongwriter.data.db.SessionEntity
import app.sunosongwriter.data.db.SessionMessageEntity
import app.sunosongwriter.data.db.SuggestionEntity
import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.domain.prompt.TemplateKey
import app.sunosongwriter.domain.review.Decision
import app.sunosongwriter.domain.review.ParsedSuggestion
import app.sunosongwriter.domain.review.Suggestion
import app.sunosongwriter.domain.review.SuggestionTarget
import app.sunosongwriter.domain.review.Verdict
import app.sunosongwriter.domain.workflow.MessageStatus
import app.sunosongwriter.domain.workflow.Session
import app.sunosongwriter.domain.workflow.SessionMessage
import app.sunosongwriter.domain.workflow.WaitingRelay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Sessions, their messages and review suggestions (spec 5.4, 5.6). */
class SessionRepository(
    private val dao: SessionDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun observeManual(variantId: Long): Flow<Session?> = dao.observe(variantId, MANUAL).map { it?.toDomain() }

    suspend fun getOrCreateManual(songId: Long, variantId: Long): Session =
        (dao.find(variantId, MANUAL) ?: dao.get(dao.insert(SessionEntity(songId = songId, variantId = variantId, type = MANUAL, fullTranscript = false, createdAt = nowMillis())))!!)
            .toDomain()

    suspend fun get(id: Long): Session? = dao.get(id)?.toDomain()

    fun observe(id: Long): Flow<Session?> = dao.observeSession(id).map { it?.toDomain() }

    fun observeRooms(songId: Long): Flow<List<Session>> = dao.observeByType(songId, ROOM).map { list -> list.map { it.toDomain() } }

    suspend fun rooms(): List<Session> = dao.allOfType(ROOM).map { it.toDomain() }

    suspend fun createRoom(songId: Long, variantId: Long, configJson: String, stateJson: String): Long =
        dao.insert(SessionEntity(songId = songId, variantId = variantId, type = ROOM, fullTranscript = false, createdAt = nowMillis(), configJson = configJson, stateJson = stateJson))

    suspend fun setState(sessionId: Long, stateJson: String) = dao.setState(sessionId, stateJson)

    suspend fun setConfig(sessionId: Long, configJson: String) = dao.setConfig(sessionId, configJson)

    suspend fun resetInterrupted(sessionId: Long): Int = dao.resetInterrupted(sessionId, nowMillis())

    suspend fun setFullTranscript(sessionId: Long, full: Boolean) = dao.setFullTranscript(sessionId, full)

    fun observeMessages(sessionId: Long): Flow<List<SessionMessage>> = dao.observeMessages(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun messages(sessionId: Long): List<SessionMessage> = dao.messages(sessionId).map { it.toDomain() }

    suspend fun message(id: Long): SessionMessage? = dao.message(id)?.toDomain()

    fun observeSuggestions(sessionId: Long): Flow<List<Suggestion>> = dao.observeSuggestions(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun suggestions(sessionId: Long): List<Suggestion> = dao.suggestions(sessionId).map { it.toDomain() }

    fun observeWaitingRelay(): Flow<List<WaitingRelay>> =
        dao.observeWaitingRelay().map { rows -> rows.map { WaitingRelay(it.message.toDomain(), it.songId, inRoom = it.sessionType == ROOM) } }

    val anyStepDone: Flow<Boolean> = dao.observeAnyStepDone()

    suspend fun insertMessage(
        sessionId: Long,
        round: Int,
        action: SessionAction,
        participantId: Long?,
        participantName: String,
        participantAvatar: String,
        modelLabel: String?,
        templateKey: TemplateKey,
        instructions: String?,
        prompt: String,
        status: MessageStatus,
        basedOnVersionId: Long?,
        targetVariantId: Long? = null,
        proposal: Boolean = false,
        snapshotId: Long? = null,
        fixesIssues: String? = null,
    ): Long {
        val now = nowMillis()
        return dao.insertMessage(
            SessionMessageEntity(
                sessionId = sessionId, round = round, action = action.name, participantId = participantId,
                participantName = participantName, participantAvatar = participantAvatar, modelLabel = modelLabel,
                templateKey = templateKey.value, instructions = instructions, prompt = prompt, content = "",
                status = status.name, error = null, basedOnVersionId = basedOnVersionId, producedVersionId = null,
                verdict = null, summary = null, parseFailed = false, inputTokens = null, outputTokens = null,
                createdAt = now, updatedAt = now, targetVariantId = targetVariantId, proposal = proposal,
                snapshotId = snapshotId, fixesIssues = fixesIssues,
            ),
        )
    }

    suspend fun updateStreaming(id: Long, content: String, status: MessageStatus) =
        dao.updateStreaming(id, content, status.name, nowMillis())

    /** Applies [change] to the stored row. */
    suspend fun update(id: Long, change: (SessionMessageEntity) -> SessionMessageEntity) {
        val row = dao.message(id) ?: return
        dao.updateMessage(change(row).copy(updatedAt = nowMillis()))
    }

    suspend fun deleteMessage(id: Long) = dao.deleteMessage(id)

    suspend fun failInterrupted(): Int = dao.failInterrupted("Interrupted: the app was closed while this step was running.", nowMillis())

    suspend fun addSuggestions(messageId: Long, participantName: String, parsed: List<ParsedSuggestion>) {
        val now = nowMillis()
        dao.insertSuggestions(
            parsed.map {
                SuggestionEntity(
                    messageId = messageId, participantName = participantName, target = it.target.name, section = it.section,
                    change = it.change, rationale = it.rationale, decision = Decision.PENDING.name, editedChange = null,
                    implementedInVersionId = null, createdAt = now,
                )
            },
        )
    }

    suspend fun decide(ids: List<Long>, decision: Decision, editedChange: String? = null) =
        dao.decide(ids, decision.name, editedChange?.trim()?.takeIf { it.isNotEmpty() })

    suspend fun markImplemented(ids: List<Long>, versionId: Long) = dao.markImplemented(ids, versionId)

    private fun SessionEntity.toDomain() = Session(id, songId, variantId, fullTranscript, type, configJson, stateJson, createdAt)

    private fun SessionMessageEntity.toDomain() = SessionMessage(
        id = id, sessionId = sessionId, round = round,
        action = SessionAction.entries.firstOrNull { it.name == action } ?: SessionAction.TWEAK,
        participantId = participantId, participantName = participantName, participantAvatar = participantAvatar,
        modelLabel = modelLabel, templateKey = TemplateKey(templateKey), instructions = instructions, prompt = prompt,
        content = content, status = MessageStatus.entries.firstOrNull { it.name == status } ?: MessageStatus.FAILED,
        error = error, basedOnVersionId = basedOnVersionId, producedVersionId = producedVersionId,
        verdict = verdict?.let { v -> Verdict.entries.firstOrNull { it.name == v } }, summary = summary,
        parseFailed = parseFailed, inputTokens = inputTokens, outputTokens = outputTokens, createdAt = createdAt,
        warning = warning, targetVariantId = targetVariantId, proposal = proposal, chosen = chosen,
        costUsd = costUsd, retryAtMillis = retryAtMillis, snapshotId = snapshotId, fixesIssues = fixesIssues,
    )

    private fun SuggestionEntity.toDomain() = Suggestion(
        id = id, messageId = messageId, participantName = participantName,
        target = SuggestionTarget.entries.firstOrNull { it.name == target } ?: SuggestionTarget.OTHER,
        section = section, change = change, rationale = rationale,
        decision = Decision.entries.firstOrNull { it.name == decision } ?: Decision.PENDING,
        editedChange = editedChange, implementedInVersionId = implementedInVersionId,
    )

    companion object {
        const val MANUAL = "manual"
        const val ROOM = "writers_room"
    }
}
