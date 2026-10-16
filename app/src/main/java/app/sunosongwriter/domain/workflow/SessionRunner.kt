package app.sunosongwriter.domain.workflow

import app.sunosongwriter.data.db.SessionMessageEntity
import app.sunosongwriter.data.llm.ChatEvent
import app.sunosongwriter.data.llm.ChatGateway
import app.sunosongwriter.data.llm.ChatMessage
import app.sunosongwriter.data.llm.ChatOptions
import app.sunosongwriter.data.llm.LlmError
import app.sunosongwriter.data.llm.UsageTag
import app.sunosongwriter.data.repo.BackendRepository
import app.sunosongwriter.data.repo.ParticipantRepository
import app.sunosongwriter.data.repo.SessionRepository
import app.sunosongwriter.data.repo.SongRepository
import app.sunosongwriter.data.repo.TemplateRepository
import app.sunosongwriter.domain.model.Author
import app.sunosongwriter.domain.model.AuthorType
import app.sunosongwriter.domain.model.ModelRef
import app.sunosongwriter.domain.model.Participant
import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.SunoSettings
import app.sunosongwriter.domain.model.Version
import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.domain.prompt.TemplateContext
import app.sunosongwriter.domain.prompt.TemplateKey
import app.sunosongwriter.domain.prompt.TemplateRenderer
import app.sunosongwriter.domain.review.Decision
import app.sunosongwriter.domain.review.ParsedContent.Part
import app.sunosongwriter.domain.review.ReplyParser
import app.sunosongwriter.domain.review.ReviewParser
import app.sunosongwriter.domain.review.SuggestionGrouping
import app.sunosongwriter.domain.results.FixContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class RunRequest(
    val songId: Long,
    val variantId: Long,
    val action: SessionAction,
    val participantId: Long,
    /** Null: use the default for this action and participant. */
    val templateKey: TemplateKey? = null,
    val instructions: String = "",
    /** Fix from results: the sent snapshot whose results to fix (spec 5.13). */
    val snapshotId: Long? = null,
)

class RunException(message: String) : Exception(message)

/**
 * Prepares and runs steps (spec 5.4, 5.5). A step is first [prepare]d: the template is rendered and the
 * message recorded, waiting for a relay reply or queued for the API. API steps then [execute], streaming
 * through [ChatGateway] and falling back to the participant's other models when one fails (spec 5.2).
 * Replies become a new version, review suggestions (spec 5.6), or Writers' Room style proposals.
 *
 * Manual mode launches [execute] in the app's [scope]. The Writers' Room worker calls it directly and
 * awaits it, which is what lets a room survive leaving the app (section 8). Every step that finishes is
 * announced on [settled], so a waiting room can carry on.
 */
class SessionRunner(
    private val scope: CoroutineScope,
    private val gateway: ChatGateway,
    private val songs: SongRepository,
    private val sessions: SessionRepository,
    private val templates: TemplateRepository,
    private val participants: ParticipantRepository,
    private val backends: BackendRepository,
    private val guide: () -> String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Fix from results: a snapshot and its logged results (spec 5.13). */
    private val fixContext: suspend (snapshotId: Long) -> FixContext? = { null },
) {
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val _settled = MutableSharedFlow<Long>(extraBufferCapacity = 64)

    /** Session ids, each time one of their steps finishes, fails or is skipped. */
    val settled: SharedFlow<Long> = _settled.asSharedFlow()

    /** Manual mode: prepares a step in the variant's manual session and starts it. Returns the message id. */
    suspend fun start(request: RunRequest): Long {
        val session = sessions.getOrCreateManual(request.songId, request.variantId)
        val messageId = prepare(session, request.action, request.participantId, request.templateKey, request.instructions, snapshotId = request.snapshotId)
        if (sessions.message(messageId)?.status == MessageStatus.PENDING) launch(messageId)
        return messageId
    }

    /**
     * Renders the template for [action] and records the step. Relay participants' steps wait for a reply;
     * others are queued as [MessageStatus.PENDING] for [execute]. Throws [RunException] when it can't start.
     */
    suspend fun prepare(
        session: Session,
        action: SessionAction,
        participantId: Long,
        templateKey: TemplateKey? = null,
        instructions: String = "",
        targetVariantId: Long? = null,
        proposal: Boolean = false,
        snapshotId: Long? = null,
        /** Chair decision: the numbered choices, for the template's `{{suggestions}}`. */
        choices: String? = null,
    ): Long {
        val participant = participants.get(participantId) ?: throw RunException("That participant no longer exists.")
        val ref = participant.model ?: throw RunException("${participant.name} has no model. Pick one in Settings → Participants.")
        val backend = backends.get(ref.backendId) ?: throw RunException("${participant.name}'s backend was deleted.")
        if (action.needsInstructions && instructions.isBlank()) throw RunException("Say what to change.")

        val variantId = targetVariantId ?: session.variantId
        val song = songs.observeSong(session.songId).first() ?: throw RunException("Song not found.")
        if (action == SessionAction.QUICK_START && song.concept.isBlank() && instructions.isBlank()) {
            throw RunException("Write the idea first: Quick start turns it into a draft.")
        }
        val current = songs.latest(variantId) ?: throw RunException("Variant not found.")
        val accepted = if (action == SessionAction.IMPLEMENT) {
            pendingAccepted(session.id).also { if (it.isEmpty()) throw RunException("Accept at least one suggestion first.") }
        } else {
            emptyList()
        }
        // Fix works on exactly what was sent, plus what came back (spec 5.13).
        val fix = if (action == SessionAction.FIX) {
            val id = snapshotId ?: throw RunException("Fix from results starts from a sent snapshot.")
            fixContext(id) ?: throw RunException("That snapshot no longer exists.")
        } else {
            null
        }
        if (fix != null && fix.results.isBlank()) throw RunException("Log at least one take first.")
        val template = templateKey?.let { templates.get(it) } ?: templates.resolve(action, participant.id)
        val prompt = TemplateRenderer.render(
            template.body,
            TemplateContext(
                title = song.title,
                artist = song.artist,
                tags = song.tags.joinToString(", "),
                concept = song.concept,
                style = fix?.snapshot?.styleText ?: current.content.style.flatText(),
                lyrics = fix?.snapshot?.lyricsText ?: current.content.lyrics,
                sliders = describeSettings(fix?.snapshot?.settings ?: current.content.settings),
                suggestions = choices ?: SuggestionGrouping.describeForImplement(accepted),
                results = fix?.results.orEmpty(),
                guide = if ("{{guide}}" in template.body.replace(" ", "")) guide() else "",
                instructions = instructions.trim(),
            ),
        )
        val round = sessions.messages(session.id).count { it.action == SessionAction.IMPLEMENT && it.producedVersionId != null } + 1
        return sessions.insertMessage(
            sessionId = session.id,
            round = round,
            action = action,
            participantId = participant.id,
            participantName = participant.name,
            participantAvatar = participant.avatar,
            modelLabel = if (backend.isRelay) backend.name else null,
            templateKey = template.key,
            instructions = instructions.trim().ifEmpty { null },
            prompt = prompt,
            status = if (backend.isRelay) MessageStatus.WAITING_RELAY else MessageStatus.PENDING,
            basedOnVersionId = fix?.snapshot?.versionId?.takeIf { songs.version(it)?.variantId == variantId } ?: current.id,
            targetVariantId = targetVariantId,
            proposal = proposal,
            snapshotId = fix?.snapshot?.id,
            fixesIssues = fix?.issues?.joinToString(", ")?.ifEmpty { null },
        )
    }

    /** Runs a queued API step to completion: the reply, any review retry, and the resulting version or suggestions. */
    suspend fun execute(messageId: Long) {
        val message = sessions.message(messageId) ?: return
        if (message.status != MessageStatus.PENDING) return
        val participant = message.participantId?.let { participants.get(it) }
        if (participant == null) {
            fail(message, "That participant no longer exists.")
            return
        }
        val session = sessions.get(message.sessionId) ?: return
        val history = if (session.fullTranscript) transcriptHistory(message) else emptyList()
        val sent = history + ChatMessage(ChatMessage.Role.USER, message.prompt)
        // Reviews and chair decisions reply in JSON; JSON mode makes that reliable where the backend has it.
        val options = ChatOptions(jsonObject = message.action == SessionAction.REVIEW || message.action == SessionAction.CHAIR)
        val answer = streamWithFallbacks(messageId, participant, sent, options) ?: return
        val updated = sessions.message(messageId) ?: return
        warnIfTruncated(updated, sent)
        handleReply(updated.copy(content = answer.text)) { firstReply ->
            // One retry for unreadable review JSON (spec 5.6), to the model that answered.
            val retry = sent + ChatMessage(ChatMessage.Role.ASSISTANT, firstReply) + ChatMessage(ChatMessage.Role.USER, JSON_RETRY)
            when (val r = stream(messageId, answer.ref, retry, options)) {
                is StreamResult.Ok -> r.text
                is StreamResult.Failed -> {
                    fail(sessions.message(messageId) ?: return@handleReply null, r.error.message)
                    null
                }
            }
        }
    }

    /** Queues a failed step to run again with the same prompt. Manual steps start straight away. */
    suspend fun retry(messageId: Long) {
        val message = sessions.message(messageId) ?: return
        sessions.update(messageId) {
            it.copy(status = MessageStatus.PENDING.name, error = null, warning = null, content = "", parseFailed = false, retryAtMillis = null)
        }
        val session = sessions.get(message.sessionId) ?: return
        if (session.type == SessionRepository.MANUAL) launch(messageId) else _settled.tryEmit(session.id)
    }

    fun cancel(messageId: Long) {
        jobs.remove(messageId)?.cancel()
        scope.launch {
            sessions.update(messageId) { it.copy(status = MessageStatus.FAILED.name, error = "Stopped.") }
            sessions.message(messageId)?.let { _settled.tryEmit(it.sessionId) }
        }
    }

    /** The chair skips a step, e.g. a relay participant who isn't going to answer. */
    suspend fun skip(messageId: Long) {
        jobs.remove(messageId)?.cancel()
        val message = sessions.message(messageId) ?: return
        sessions.update(messageId) { it.copy(status = MessageStatus.SKIPPED.name, error = null) }
        _settled.tryEmit(message.sessionId)
    }

    /** The user pasted or shared the chat app's reply to a relay step (spec 5.2). */
    suspend fun submitRelayReply(messageId: Long, reply: String) {
        val message = sessions.message(messageId) ?: return
        sessions.update(messageId) { it.copy(content = reply.trim(), status = MessageStatus.STREAMING.name, error = null) }
        handleReply(message.copy(content = reply.trim()), retryReview = null)
    }

    /** Writers' Room: the chair picks a style proposal, which becomes the next version (spec 5.5 step 2). */
    suspend fun adoptProposal(messageId: Long): Version? {
        val message = sessions.message(messageId) ?: return null
        val session = sessions.get(message.sessionId) ?: return null
        val latest = songs.latest(session.variantId) ?: return null
        val parsed = ReplyParser.parse(message.content, latest.content, message.action) ?: return null
        val content = merge(latest.content, parsed.content, parsed.changedParts)
        val version = songs.saveVersion(
            session.variantId, content, authorOf(message),
            "Style by ${message.participantName}, picked by the chair", step = "Style proposal",
        )
        sessions.update(messageId) { it.copy(chosen = true, producedVersionId = version?.id) }
        return version
    }

    private fun launch(messageId: Long) {
        jobs[messageId] = scope.launch {
            try {
                execute(messageId)
            } finally {
                jobs.remove(messageId)
            }
        }
    }

    private sealed interface StreamResult {
        data class Ok(val text: String) : StreamResult
        data class Failed(val error: LlmError) : StreamResult
    }

    private data class Answer(val text: String, val ref: ModelRef)

    /**
     * Tries the participant's model, then each fallback in order, until one answers (spec 5.2).
     * Relay fallbacks are skipped: they need the user. Free-only mode is enforced by the gateway, so a
     * paid fallback is refused rather than used.
     */
    private suspend fun streamWithFallbacks(messageId: Long, participant: Participant, sent: List<ChatMessage>, options: ChatOptions): Answer? {
        val refs = listOfNotNull(participant.model) + participant.fallbacks
        val failures = mutableListOf<String>()
        var retryAt: Long? = null
        for ((index, ref) in refs.withIndex()) {
            val backend = backends.get(ref.backendId)
            if (backend == null || backend.isRelay) continue
            when (val result = stream(messageId, ref, sent, options)) {
                is StreamResult.Ok -> {
                    if (index > 0) {
                        sessions.update(messageId) { it.copy(warning = "Answered by a fallback, because: ${failures.joinToString("; ")}") }
                    }
                    return Answer(result.text, ref)
                }
                is StreamResult.Failed -> {
                    failures += "${ref.modelId}: ${result.error.message}"
                    (result.error as? LlmError.QuotaExhausted)?.resetAtMillis?.let { retryAt = maxOf(retryAt ?: 0L, it) }
                }
            }
        }
        val message = sessions.message(messageId) ?: return null
        fail(message, failures.joinToString("\n").ifEmpty { "No model to run this step." }, retryAt)
        return null
    }

    /** Streams one reply into the message row. */
    private suspend fun stream(messageId: Long, ref: ModelRef, sent: List<ChatMessage>, options: ChatOptions = ChatOptions()): StreamResult {
        val text = StringBuilder()
        var lastWrite = 0L
        val model = backends.resolve(ref)?.let { t -> "${t.model?.displayName ?: ref.modelId} · ${t.backend.name}" }
        val tag = sessions.message(messageId)?.let { m -> UsageTag(sessions.get(m.sessionId)?.songId, m.sessionId, messageId) } ?: UsageTag(messageId = messageId)
        sessions.update(messageId) { it.copy(status = MessageStatus.STREAMING.name, modelLabel = model ?: it.modelLabel, content = "", error = null) }
        var failed: LlmError? = null
        gateway.stream(ref, sent, options, tag).collect { event ->
            when (event) {
                is ChatEvent.Delta -> {
                    text.append(event.text)
                    if (nowMillis() - lastWrite > STREAM_WRITE_MILLIS) {
                        lastWrite = nowMillis()
                        sessions.updateStreaming(messageId, text.toString(), MessageStatus.STREAMING)
                    }
                }
                is ChatEvent.RateLimited -> sessions.update(messageId) {
                    it.copy(status = MessageStatus.RATE_LIMITED.name, error = "Rate limited: waiting ~${event.waitSeconds}s" + if (event.attempt > 0) " (retry ${event.attempt})" else "")
                }
                is ChatEvent.Usage -> sessions.update(messageId) {
                    it.copy(
                        inputTokens = (it.inputTokens ?: 0) + (event.inputTokens ?: 0),
                        outputTokens = (it.outputTokens ?: 0) + (event.outputTokens ?: 0),
                        costUsd = event.costUsd?.let { c -> (it.costUsd ?: 0.0) + c } ?: it.costUsd,
                    )
                }
                is ChatEvent.Completed -> sessions.updateStreaming(messageId, text.toString(), MessageStatus.STREAMING)
                is ChatEvent.Failed -> failed = event.error
            }
        }
        failed?.let { return StreamResult.Failed(it) }
        sessions.update(messageId) { it.copy(error = null) }
        return StreamResult.Ok(text.toString())
    }

    private suspend fun fail(message: SessionMessage, error: String, retryAt: Long? = null) {
        sessions.update(message.id) { it.copy(status = MessageStatus.FAILED.name, error = error, retryAtMillis = retryAt) }
        _settled.tryEmit(message.sessionId)
    }

    private suspend fun done(message: SessionMessage, change: (SessionMessageEntity) -> SessionMessageEntity) {
        sessions.update(message.id) { change(it).copy(status = MessageStatus.DONE.name) }
        _settled.tryEmit(message.sessionId)
    }

    /**
     * Turns a finished reply into suggestions, a style proposal, or a version. [retryReview] re-asks for
     * review JSON once; relay replies pass null, and an unreadable reply is kept for the user to add
     * suggestions by hand.
     */
    private suspend fun handleReply(message: SessionMessage, retryReview: (suspend (String) -> String?)?) {
        if (message.action == SessionAction.CHAIR) {
            // The room reads the decision from the reply.
            done(message) { it.copy(content = message.content) }
            return
        }
        if (message.action == SessionAction.REVIEW) {
            var reply = message.content
            var review = ReviewParser.parse(reply)
            if (review == null && retryReview != null) {
                reply = retryReview(reply) ?: return
                review = ReviewParser.parse(reply)
            }
            if (review == null) {
                done(message) { it.copy(content = reply, parseFailed = true) }
                return
            }
            sessions.addSuggestions(message.id, message.participantName, review.suggestions)
            done(message) { it.copy(content = reply, verdict = review.verdict.name, summary = review.summary, parseFailed = false) }
            return
        }

        val session = sessions.get(message.sessionId) ?: return
        val variantId = message.targetVariantId ?: session.variantId
        val base = message.basedOnVersionId?.let { id -> songs.observeVersions(session.songId).first().firstOrNull { it.id == id } }
            ?: songs.latest(variantId)
            ?: return
        val parsed = ReplyParser.parse(message.content, base.content, message.action)
        if (parsed == null) {
            fail(message, "Couldn't find a style prompt or lyrics in the reply. The template asks for STYLE: and LYRICS: headings.")
            return
        }
        if (message.proposal) {
            // Kept for the chair to compare; only the chosen proposal becomes a version.
            done(message) { it }
            return
        }
        // Applied on top of the newest version, so edits made while the step ran aren't lost to an older base.
        val latest = songs.latest(variantId)?.content ?: base.content
        val content = merge(latest, parsed.content, parsed.changedParts)
        val note = fixNote(message, parsed.note) ?: parsed.note ?: "${message.action.label} by ${message.participantName}"
        val version = songs.saveVersion(variantId, content, authorOf(message), note, step = message.action.label)
        if (message.action == SessionAction.IMPLEMENT && version != null) {
            sessions.markImplemented(pendingAccepted(session.id).map { it.id }, version.id)
        }
        if (message.action == SessionAction.QUICK_START) fillSongDetails(session.songId, parsed.title, parsed.tags)
        done(message) { it.copy(producedVersionId = version?.id, error = if (version == null) "The reply didn't change anything." else null) }
    }

    /** Quick start names the song and tags its genre, but never overwrites what the user already set. */
    private suspend fun fillSongDetails(songId: Long, title: String?, tags: List<String>) {
        val song = songs.observeSong(songId).first() ?: return
        val newTitle = title?.takeIf { song.title.isBlank() }
        val newTags = tags.takeIf { song.tags.isEmpty() && it.isNotEmpty() }
        if (newTitle == null && newTags == null) return
        songs.updateSong(song.copy(title = newTitle ?: song.title, tags = newTags ?: song.tags))
    }

    /** Fix from results: the change note lists the issues it targets (spec 5.13). */
    private fun fixNote(message: SessionMessage, modelNote: String?): String? {
        if (message.action != SessionAction.FIX) return null
        val issues = message.fixesIssues ?: "the notes from Suno"
        return "Fixes $issues" + (modelNote?.let { ": $it" } ?: "")
    }

    private fun authorOf(message: SessionMessage) =
        Author(AuthorType.MODEL, listOfNotNull(message.participantName, message.modelLabel).joinToString(" · "), message.participantId)

    /**
     * Providers report how many prompt tokens they read. Far fewer than the prompt's size means the
     * model's context window cut it off: Ollama silently keeps only the end of a long prompt (its
     * default window is 4,096 tokens, and the OpenAI-compatible API can't raise it per request).
     */
    private suspend fun warnIfTruncated(message: SessionMessage, sent: List<ChatMessage>) {
        val read = message.inputTokens ?: return
        val estimate = estimateTokens(sent.sumOf { it.content.length })
        if (!looksTruncated(read, estimate)) return
        sessions.update(message.id) {
            it.copy(warning = "The model only read about $read of ~$estimate prompt tokens: its context window is smaller than this prompt, so it missed part of it. For Ollama, raise the context length (Ollama app settings, or OLLAMA_CONTEXT_LENGTH) to at least ${roundUp(estimate)}.")
        }
    }

    private fun merge(latest: SongContent, parsed: SongContent, parts: Set<Part>): SongContent {
        var out = latest
        if (Part.STYLE in parts) out = out.copy(style = parsed.style)
        if (Part.LYRICS in parts) out = out.copy(lyrics = parsed.lyrics)
        if (Part.SETTINGS in parts) out = out.copy(settings = parsed.settings)
        return out
    }

    private suspend fun pendingAccepted(sessionId: Long) =
        sessions.suggestions(sessionId).filter { it.decision == Decision.ACCEPTED && it.implementedInVersionId == null }

    /** Earlier finished steps as conversation history, for sessions using the full transcript (spec 5.17). */
    private suspend fun transcriptHistory(current: SessionMessage): List<ChatMessage> =
        sessions.messages(current.sessionId)
            .filter { it.id != current.id && it.status == MessageStatus.DONE && it.content.isNotBlank() }
            .takeLast(MAX_HISTORY_STEPS)
            .flatMap { listOf(ChatMessage(ChatMessage.Role.USER, it.prompt), ChatMessage(ChatMessage.Role.ASSISTANT, it.content)) }

    companion object {
        private const val STREAM_WRITE_MILLIS = 300L
        private const val MAX_HISTORY_STEPS = 6
        const val JSON_RETRY = "That reply couldn't be read as JSON. Reply again with ONLY the JSON object described above: no other text, no code fences."

        /** About four characters per token for English prose. Rough, but enough to spot a cut-off prompt. */
        fun estimateTokens(chars: Int): Int = chars / 4

        fun looksTruncated(readTokens: Int, estimatedTokens: Int): Boolean =
            estimatedTokens > 1_000 && readTokens < estimatedTokens * 0.7

        private fun roundUp(tokens: Int): Int = listOf(4_096, 8_192, 16_384, 32_768, 65_536).firstOrNull { it >= tokens * 1.25 } ?: 131_072

        fun describeSettings(s: SunoSettings): String = s.describe().joinToString("\n") { (label, value) -> "$label: ${value ?: "not set"}" }
    }
}
