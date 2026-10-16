package app.sunosongwriter.domain.workflow

import app.sunosongwriter.data.repo.SessionRepository
import app.sunosongwriter.data.repo.SongRepository
import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.domain.review.Decision
import app.sunosongwriter.domain.review.SuggestionGroup
import app.sunosongwriter.domain.review.SuggestionGrouping
import app.sunosongwriter.domain.review.Verdict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a Writers' Room run is set up (spec 5.5). */
@Serializable
data class RoomConfig(
    /** Who reviews each round, and proposes styles. */
    val reviewerIds: List<Long>,
    /** Writes base lyrics first; null skips that step (e.g. the song already has lyrics). */
    val lyricistId: Long?,
    /** Every reviewer proposes a style and the chair picks one. */
    val proposeStyles: Boolean,
    val implementerId: Long,
    /** Runs Finalise into a `Framework-refined` variant; null skips it. */
    val finaliserId: Long?,
    val roundCap: Int = 3,
    /** Who decides at the style pick and after each review round (Q2 default: you). */
    val chair: ChairMode = ChairMode.YOU,
    /** Automatic chair: suggestions backed by at least this many reviewers are accepted. */
    val autoAcceptBackers: Int = 2,
    /** Chair mode [ChairMode.MODEL]: the participant who decides. */
    val chairId: Long? = null,
)

@Serializable
enum class RoomStage(val label: String) {
    LYRICS("Writing lyrics"),
    STYLE_PROPOSALS("Proposing styles"),
    STYLE_PICK("Pick a style"),
    REVIEW("Reviewing"),
    REVIEW_DECISION("Decide on suggestions"),
    IMPLEMENT("Implementing"),
    FINALISE("Finalising"),
    DONE("Done"),
    STOPPED("Stopped"),
    ;

    /** Stages where the chair has to act before anything else happens. */
    val waitsForChair: Boolean get() = this == STYLE_PICK || this == REVIEW_DECISION
    val isOver: Boolean get() = this == DONE || this == STOPPED
}

@Serializable
data class RoomPause(val reason: String, val resumeAtMillis: Long? = null)

@Serializable
data class RoomState(
    val stage: RoomStage,
    val round: Int = 1,
    val consensusVariantId: Long,
    val refinedVariantId: Long? = null,
    /** The current stage's steps. Empty means they haven't been started yet. */
    val stepIds: List<Long> = emptyList(),
    val pause: RoomPause? = null,
    val endedBecause: String? = null,
    /** A chair participant's decision step at the current chair stage. */
    val chairStepId: Long? = null,
    /** What the chair (automatic or a participant) decided last, for the transcript. */
    val lastChairNote: String? = null,
)

/** What the room is waiting on after [WritersRoom.advance]. */
sealed interface RoomStatus {
    /** API steps to run now. */
    data class Run(val messageIds: List<Long>) : RoomStatus
    data object WaitingForRelay : RoomStatus
    data object WaitingForChair : RoomStatus
    data class Paused(val pause: RoomPause) : RoomStatus
    data object Over : RoomStatus
}

/**
 * The Writers' Room (spec section 2, 5.5): lyrics → style proposals → chair picks → review rounds
 * (reviews → chair decides → implement) until everyone approves, the round cap is reached or the chair
 * ends it → Finalise into a `Framework-refined` variant.
 *
 * [advance] is idempotent: it reads the saved state and the current stage's steps, starts steps that
 * haven't started, and moves on only when every step has settled. Resuming after process death is just
 * calling it again.
 */
class WritersRoom(
    private val runner: SessionRunner,
    private val sessions: SessionRepository,
    private val songs: SongRepository,
    private val json: Json,
) {
    private val mutex = Mutex()

    /** Creates the `Consensus` variant from [sourceVariantId]'s current version and a room session on it. */
    suspend fun create(songId: Long, sourceVariantId: Long, config: RoomConfig): Long {
        require(config.reviewerIds.isNotEmpty()) { "Pick at least one reviewer." }
        val source = songs.latest(sourceVariantId) ?: error("Variant not found")
        val consensus = songs.createVariant(songId, uniqueVariantName(songId, CONSENSUS), source.id)
        val stage = when {
            config.lyricistId != null -> RoomStage.LYRICS
            config.proposeStyles -> RoomStage.STYLE_PROPOSALS
            else -> RoomStage.REVIEW
        }
        return sessions.createRoom(songId, consensus, json.encodeToString(config), json.encodeToString(RoomState(stage, consensusVariantId = consensus)))
    }

    fun config(session: Session): RoomConfig = json.decodeFromString(session.configJson)

    fun state(session: Session): RoomState = json.decodeFromString(session.stateJson)

    suspend fun advance(sessionId: Long): RoomStatus = mutex.withLock { advanceLocked(sessionId, depth = 0) }

    private suspend fun advanceLocked(sessionId: Long, depth: Int): RoomStatus {
        check(depth < 20) { "Writers' Room state machine didn't settle" }
        val session = sessions.get(sessionId) ?: return RoomStatus.Over
        val config = config(session)
        var state = state(session)
        state.pause?.let { return RoomStatus.Paused(it) }
        if (state.stage.isOver) return RoomStatus.Over

        if (state.stepIds.isEmpty() && !state.stage.waitsForChair) {
            val started = try {
                startStage(session, config, state)
            } catch (e: RunException) {
                return pause(sessionId, state, "Couldn't start ${state.stage.label.lowercase()}: ${e.message}")
            }
            if (started == null) {
                // Nothing to run in this stage (e.g. no finaliser): move straight on.
                state = transition(session, config, state)
                save(sessionId, state)
                return advanceLocked(sessionId, depth + 1)
            }
            state = started
            save(sessionId, state)
        }

        val steps = state.stepIds.mapNotNull { sessions.message(it) }
        val queued = steps.filter { it.status.isActive }.map { it.id }
        if (queued.isNotEmpty()) return RoomStatus.Run(queued)
        if (steps.any { it.status == MessageStatus.WAITING_RELAY }) return RoomStatus.WaitingForRelay
        steps.filter { it.status == MessageStatus.FAILED }.takeIf { it.isNotEmpty() }?.let { failed ->
            val resumeAt = failed.mapNotNull { it.retryAtMillis }.maxOrNull()
            val who = failed.joinToString { it.participantName }
            return pause(sessionId, state, "$who couldn't finish: ${failed.first().error ?: "unknown error"}", resumeAt)
        }
        if (state.stage.waitsForChair) {
            val decided = decideForChair(session, config, state) ?: return chairStatus(sessionId)
            save(sessionId, decided)
            return advanceLocked(sessionId, depth + 1)
        }

        // Every step settled: on to the next stage.
        state = transition(session, config, state)
        save(sessionId, state)
        return advanceLocked(sessionId, depth + 1)
    }

    /** Starts the stage's steps. Returns null when the stage has nothing to run. */
    private suspend fun startStage(session: Session, config: RoomConfig, state: RoomState): RoomState? = when (state.stage) {
        RoomStage.LYRICS -> state.copy(stepIds = listOf(runner.prepare(session, SessionAction.WRITE_LYRICS, config.lyricistId ?: return null)))
        RoomStage.STYLE_PROPOSALS -> state.copy(stepIds = config.reviewerIds.map { runner.prepare(session, SessionAction.WRITE_STYLE, it, proposal = true) })
        RoomStage.REVIEW -> state.copy(stepIds = config.reviewerIds.map { runner.prepare(session, SessionAction.REVIEW, it) })
        RoomStage.IMPLEMENT -> state.copy(stepIds = listOf(runner.prepare(session, SessionAction.IMPLEMENT, config.implementerId)))
        RoomStage.FINALISE -> {
            val finaliser = config.finaliserId ?: return null
            val consensusLatest = songs.latest(state.consensusVariantId) ?: return null
            val refined = state.refinedVariantId ?: songs.createVariant(session.songId, uniqueVariantName(session.songId, REFINED), consensusLatest.id)
            state.copy(refinedVariantId = refined, stepIds = listOf(runner.prepare(session, SessionAction.FINALISE, finaliser, targetVariantId = refined)))
        }
        RoomStage.STYLE_PICK, RoomStage.REVIEW_DECISION, RoomStage.DONE, RoomStage.STOPPED -> null
    }

    /** Where the room goes once the current stage's steps have all settled. */
    private suspend fun transition(session: Session, config: RoomConfig, state: RoomState): RoomState {
        val next = when (state.stage) {
            RoomStage.LYRICS -> if (config.proposeStyles) RoomStage.STYLE_PROPOSALS else RoomStage.REVIEW
            RoomStage.STYLE_PROPOSALS -> {
                val usable = state.stepIds.mapNotNull { sessions.message(it) }.any { it.status == MessageStatus.DONE }
                if (usable) return state.copy(stage = RoomStage.STYLE_PICK, chairStepId = null) // keep the proposals as the stage's steps
                RoomStage.REVIEW
            }
            RoomStage.REVIEW -> {
                val reviews = state.stepIds.mapNotNull { sessions.message(it) }.filter { it.status == MessageStatus.DONE }
                val suggestions = sessions.suggestions(session.id).filter { s -> reviews.any { it.id == s.messageId } }
                val allApprove = reviews.isNotEmpty() && reviews.all { it.verdict == Verdict.APPROVE && !it.parseFailed } && suggestions.isEmpty()
                if (allApprove) {
                    return state.copy(stage = RoomStage.FINALISE, stepIds = emptyList(), endedBecause = "Everyone approved in round ${state.round}.")
                }
                return state.copy(stage = RoomStage.REVIEW_DECISION, chairStepId = null) // keep the reviews as the stage's steps
            }
            RoomStage.IMPLEMENT -> {
                if (state.round >= config.roundCap) {
                    return state.copy(stage = RoomStage.FINALISE, stepIds = emptyList(), endedBecause = "Reached the cap of ${config.roundCap} rounds.")
                }
                return state.copy(stage = RoomStage.REVIEW, round = state.round + 1, stepIds = emptyList())
            }
            RoomStage.FINALISE -> RoomStage.DONE
            RoomStage.STYLE_PICK, RoomStage.REVIEW_DECISION, RoomStage.DONE, RoomStage.STOPPED -> state.stage
        }
        return state.copy(stage = next, stepIds = emptyList())
    }

    // Chair actions (spec 5.5: the chair decides; the user by default).

    /** Adopts a style proposal ([messageId]), or keeps the current style (null), then starts reviewing. */
    suspend fun pickStyle(sessionId: Long, messageId: Long?) = chairAction(sessionId, RoomStage.STYLE_PICK) { state -> applyStylePick(state, messageId) }

    /** Implements the accepted suggestions; with none accepted, this round counts as the end of the debate. */
    suspend fun implementAccepted(sessionId: Long) = chairAction(sessionId, RoomStage.REVIEW_DECISION) { state -> applyImplement(sessionId, state) }

    /** The user takes the chair back (or hands it over) mid-run. A pending chair step is left as it is. */
    suspend fun setChair(sessionId: Long, mode: ChairMode) = mutex.withLock {
        val session = sessions.get(sessionId) ?: return@withLock
        sessions.setConfig(sessionId, json.encodeToString(config(session).copy(chair = mode)))
    }

    private suspend fun applyStylePick(state: RoomState, messageId: Long?): RoomState {
        messageId?.let { runner.adoptProposal(it) }
        return state.copy(stage = RoomStage.REVIEW, stepIds = emptyList(), chairStepId = null)
    }

    private suspend fun applyImplement(sessionId: Long, state: RoomState): RoomState {
        val accepted = sessions.suggestions(sessionId).count { it.decision == Decision.ACCEPTED && it.implementedInVersionId == null }
        return if (accepted == 0) state.copy(stage = RoomStage.FINALISE, stepIds = emptyList(), chairStepId = null, endedBecause = "The chair accepted no changes in round ${state.round}.")
        else state.copy(stage = RoomStage.IMPLEMENT, stepIds = emptyList(), chairStepId = null)
    }

    /** The current round's suggestions, grouped as the chair sees them. */
    private suspend fun roundGroups(sessionId: Long, state: RoomState) =
        SuggestionGrouping.group(sessions.suggestions(sessionId).filter { it.messageId in state.stepIds && it.implementedInVersionId == null })

    /** What a chair stage is waiting on: the user, or a chair participant's step (which may need running). */
    private suspend fun chairStatus(sessionId: Long): RoomStatus {
        val state = state(sessions.get(sessionId) ?: return RoomStatus.Over)
        state.pause?.let { return RoomStatus.Paused(it) }
        val step = state.chairStepId?.let { sessions.message(it) } ?: return RoomStatus.WaitingForChair
        return when {
            step.status.isActive -> RoomStatus.Run(listOf(step.id))
            step.status == MessageStatus.WAITING_RELAY -> RoomStatus.WaitingForRelay
            step.status == MessageStatus.FAILED -> pause(sessionId, state, "${step.participantName} couldn't decide as chair: ${step.error ?: "unknown error"}", step.retryAtMillis)
            else -> RoomStatus.WaitingForChair
        }
    }

    /**
     * Makes the chair's decision when the chair isn't the user (spec 5.5, auto chair). Returns the new
     * state, or null to wait: for the user, or for a chair participant's step still running.
     */
    private suspend fun decideForChair(session: Session, config: RoomConfig, state: RoomState): RoomState? = when (config.chair) {
        ChairMode.YOU -> null
        ChairMode.AUTO -> decideAutomatically(session.id, config, state)
        ChairMode.MODEL -> decideByParticipant(session, config, state)
    }

    private suspend fun decideAutomatically(sessionId: Long, config: RoomConfig, state: RoomState): RoomState {
        val steps = state.stepIds.mapNotNull { sessions.message(it) }.filter { it.status == MessageStatus.DONE }
        return when (state.stage) {
            RoomStage.STYLE_PICK -> {
                val pick = ChairRules.mostDetailedProposal(steps)
                val name = steps.firstOrNull { it.id == pick }?.participantName
                applyStylePick(state, pick).copy(lastChairNote = name?.let { "Automatic chair picked $it's style, the most detailed." } ?: "Automatic chair kept the current style.")
            }
            else -> {
                val groups = roundGroups(sessionId, state)
                val needed = ChairRules.backersNeeded(config.autoAcceptBackers, steps.size)
                decideSuggestions(sessionId, groups, ChairRules.autoAccept(groups, needed))
                val accepted = groups.count { g -> sessions.suggestions(sessionId).firstOrNull { it.id == g.lead.id }?.decision == Decision.ACCEPTED }
                applyImplement(sessionId, state).copy(lastChairNote = "Automatic chair accepted $accepted of ${groups.size} suggestions (backed by at least $needed).")
            }
        }
    }

    /** A chair participant decides through a step; an unreadable reply leaves the decision to the user. */
    private suspend fun decideByParticipant(session: Session, config: RoomConfig, state: RoomState): RoomState? {
        val chairId = config.chairId ?: return null
        val steps = state.stepIds.mapNotNull { sessions.message(it) }.filter { it.status == MessageStatus.DONE }
        val proposals = steps.takeIf { state.stage == RoomStage.STYLE_PICK }.orEmpty()
        val groups = if (state.stage == RoomStage.REVIEW_DECISION) roundGroups(session.id, state) else emptyList()
        if (state.stage == RoomStage.REVIEW_DECISION && groups.isEmpty()) return applyImplement(session.id, state)
        val stepId = state.chairStepId ?: run {
            val choices = if (state.stage == RoomStage.STYLE_PICK) ChairRules.describeProposals(proposals) else ChairRules.describeSuggestions(groups)
            val id = try {
                runner.prepare(session, SessionAction.CHAIR, chairId, choices = choices)
            } catch (e: RunException) {
                save(session.id, state.copy(pause = RoomPause("The chair couldn't start: ${e.message}")))
                return null
            }
            save(session.id, state.copy(chairStepId = id))
            return null // the worker runs the step, then advances again
        }
        val step = sessions.message(stepId) ?: return null
        if (step.status != MessageStatus.DONE) return null
        val decision = ChairParser.parse(step.content)
        if (decision == null) {
            if (step.error == null) sessions.update(stepId) { it.copy(error = "Couldn't read the chair's decision. Decide yourself below.") }
            return null
        }
        return when (state.stage) {
            RoomStage.STYLE_PICK -> {
                val chosen = decision.pick?.let { proposals.getOrNull(it - 1) }
                applyStylePick(state, chosen?.id).copy(lastChairNote = "${step.participantName} picked ${chosen?.let { "${it.participantName}'s style" } ?: "the current style"}.")
            }
            else -> {
                val accepted = decision.accept.orEmpty().mapNotNull { groups.getOrNull(it - 1) }
                decideSuggestions(session.id, groups, accepted)
                applyImplement(session.id, state).copy(lastChairNote = "${step.participantName} accepted ${accepted.size} of ${groups.size} suggestions.")
            }
        }
    }

    /** Accepts [accept] and rejects the other undecided groups. Groups the user already decided keep their decision. */
    private suspend fun decideSuggestions(sessionId: Long, groups: List<SuggestionGroup>, accept: List<SuggestionGroup>) {
        val pending = groups.filter { it.decision == Decision.PENDING }
        sessions.decide(pending.filter { it in accept }.flatMap { it.ids }, Decision.ACCEPTED)
        sessions.decide(pending.filter { it !in accept }.flatMap { it.ids }, Decision.REJECTED)
    }

    /** Ends the debate and goes straight to Finalise. */
    suspend fun finishDebate(sessionId: Long) = mutex.withLock {
        val session = sessions.get(sessionId) ?: return@withLock
        val state = state(session)
        if (state.stage.isOver || state.stage == RoomStage.FINALISE) return@withLock
        save(sessionId, state.copy(stage = RoomStage.FINALISE, stepIds = emptyList(), pause = null, endedBecause = "The chair ended the debate in round ${state.round}."))
    }

    /** Clears a pause and queues the failed steps to run again. */
    suspend fun resume(sessionId: Long) = mutex.withLock {
        val session = sessions.get(sessionId) ?: return@withLock
        val state = state(session)
        (state.stepIds + listOfNotNull(state.chairStepId)).mapNotNull { sessions.message(it) }.filter { it.status == MessageStatus.FAILED }.forEach { runner.retry(it.id) }
        save(sessionId, state.copy(pause = null))
    }

    suspend fun stop(sessionId: Long) = mutex.withLock {
        val session = sessions.get(sessionId) ?: return@withLock
        save(sessionId, state(session).copy(stage = RoomStage.STOPPED, pause = null, endedBecause = "Stopped by the chair."))
    }

    private suspend fun chairAction(sessionId: Long, expected: RoomStage, change: suspend (RoomState) -> RoomState) = mutex.withLock {
        val session = sessions.get(sessionId) ?: return@withLock
        val state = state(session)
        if (state.stage != expected) return@withLock
        save(sessionId, change(state))
    }

    private suspend fun pause(sessionId: Long, state: RoomState, reason: String, resumeAt: Long? = null): RoomStatus {
        val pause = RoomPause(reason, resumeAt)
        save(sessionId, state.copy(pause = pause))
        return RoomStatus.Paused(pause)
    }

    private suspend fun save(sessionId: Long, state: RoomState) = sessions.setState(sessionId, json.encodeToString(state))

    private suspend fun uniqueVariantName(songId: Long, base: String): String {
        val names = songs.observeVariants(songId).first().map { it.name }.toSet()
        if (base !in names) return base
        return (2..99).map { "$base $it" }.first { it !in names }
    }

    companion object {
        const val CONSENSUS = "Consensus"
        const val REFINED = "Framework-refined"

        /** Requests a run will make, by participant: for the pre-run check (spec 5.2). Excludes retries. */
        fun estimateRequests(config: RoomConfig): Map<Long, Int> {
            val counts = HashMap<Long, Int>()
            fun add(id: Long?, n: Int) { if (id != null) counts[id] = (counts[id] ?: 0) + n }
            add(config.lyricistId, 1)
            config.reviewerIds.forEach { add(it, config.roundCap + if (config.proposeStyles) 1 else 0) }
            add(config.implementerId, config.roundCap)
            add(config.finaliserId, 1)
            if (config.chair == ChairMode.MODEL) add(config.chairId, config.roundCap + if (config.proposeStyles) 1 else 0)
            return counts
        }
    }
}
