package app.sunosongwriter.ui.song

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sunosongwriter.AppContainer
import app.sunosongwriter.domain.model.Participant
import app.sunosongwriter.domain.prompt.PromptTemplate
import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.domain.prompt.TemplateKey
import app.sunosongwriter.domain.review.Decision
import app.sunosongwriter.domain.review.ParsedSuggestion
import app.sunosongwriter.domain.review.SuggestionGroup
import app.sunosongwriter.domain.review.SuggestionGrouping
import app.sunosongwriter.domain.review.SuggestionTarget
import app.sunosongwriter.domain.workflow.RunException
import app.sunosongwriter.domain.workflow.RunRequest
import app.sunosongwriter.domain.workflow.Session
import app.sunosongwriter.domain.workflow.SessionMessage
import app.sunosongwriter.ui.SongRoute
import app.sunosongwriter.ui.components.buildModelOptions
import app.sunosongwriter.ui.components.describeRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A participant as offered in the run dialog. */
data class TeamMember(
    val participant: Participant,
    val modelLabel: String,
    val isFree: Boolean?,
    val relayPackage: String?,
    /** Why it can't run right now (no model, paid model in free-only mode), or null. */
    val blockedReason: String?,
    /** The backend's base URL, to know whether the local network permission is needed. */
    val baseUrl: String? = null,
) {
    val isRelay: Boolean get() = relayPackage != null
}

/** A Writers' Room run on this song, for the Team tab's list. */
data class RoomSummary(val sessionId: Long, val label: String, val active: Boolean)

data class TeamUi(
    val rooms: List<RoomSummary> = emptyList(),
    val session: Session?,
    val messages: List<SessionMessage>,
    /** This round's suggestions (since the last implement), de-duplicated (spec 5.6). */
    val groups: List<SuggestionGroup>,
    val acceptedCount: Int,
    val members: List<TeamMember>,
    val templates: List<PromptTemplate>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TeamViewModel(savedState: SavedStateHandle, private val c: AppContainer) : ViewModel(), StepActions {
    private val songId = savedState.toRoute<SongRoute>().songId
    private val variantId = MutableStateFlow<Long?>(null)
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val session = variantId.flatMapLatest { id -> id?.let { c.sessions.observeManual(it) } ?: flowOf(null) }
    private val messages = session.flatMapLatest { s -> s?.let { c.sessions.observeMessages(it.id) } ?: flowOf(emptyList()) }
    private val suggestions = session.flatMapLatest { s -> s?.let { c.sessions.observeSuggestions(it.id) } ?: flowOf(emptyList()) }

    private val members = observeTeam(c)

    private val rooms = c.sessions.observeRooms(songId).map { list ->
        list.map { s ->
            val state = c.room.state(s)
            val label = state.pause?.let { "Paused · ${it.reason}" } ?: (state.stage.label + if (state.stage.isOver) "" else " · round ${state.round}")
            RoomSummary(s.id, label, !state.stage.isOver)
        }
    }

    val ui: StateFlow<TeamUi?> = combine(session, messages, suggestions, members, c.templates.templates) { session, messages, suggestions, members, templates ->
        val lastImplement = messages.lastOrNull { it.action == SessionAction.IMPLEMENT && it.producedVersionId != null }
        val reviewIds = messages.filter { it.action == SessionAction.REVIEW && (lastImplement == null || it.createdAt > lastImplement.createdAt) }.map { it.id }.toSet()
        val current = suggestions.filter { it.messageId in reviewIds && it.implementedInVersionId == null }
        TeamUi(
            rooms = emptyList(),
            session = session,
            messages = messages,
            groups = SuggestionGrouping.group(current),
            acceptedCount = current.count { it.decision == Decision.ACCEPTED },
            members = members,
            templates = templates,
        )
    }.combine(rooms) { team, rooms -> team.copy(rooms = rooms) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setVariant(id: Long) {
        variantId.value = id
    }

    fun clearMessage() {
        _message.value = null
    }

    fun run(action: SessionAction, participantId: Long, templateKey: TemplateKey?, instructions: String) {
        val variant = variantId.value ?: return
        viewModelScope.launch {
            try {
                c.runner.start(RunRequest(songId, variant, action, participantId, templateKey, instructions))
            } catch (e: RunException) {
                _message.value = e.message
            }
        }
    }

    /** Quick start: saves the idea as the song's concept, then one model drafts everything from it. */
    fun quickStart(request: QuickStartRequest, onStarted: () -> Unit) {
        val variant = variantId.value ?: return
        viewModelScope.launch {
            try {
                val song = c.songs.observeSong(songId).first() ?: return@launch
                if (song.concept != request.idea) c.songs.updateSong(song.copy(concept = request.idea))
                c.runner.start(RunRequest(songId, variant, SessionAction.QUICK_START, request.participantId, instructions = request.instructions))
                onStarted()
            } catch (e: RunException) {
                _message.value = e.message
            }
        }
    }

    override fun retry(messageId: Long) { viewModelScope.launch { c.runner.retry(messageId) } }

    override fun stop(messageId: Long) = c.runner.cancel(messageId)

    override fun delete(messageId: Long) { viewModelScope.launch { c.sessions.deleteMessage(messageId) } }

    override fun submitRelay(messageId: Long, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { c.runner.submitRelayReply(messageId, text) }
    }

    fun setFullTranscript(full: Boolean) = viewModelScope.launch {
        val variant = variantId.value ?: return@launch
        val s = c.sessions.getOrCreateManual(songId, variant)
        c.sessions.setFullTranscript(s.id, full)
    }

    // Suggestions (spec 5.6). A decision applies to every suggestion in the group.

    override fun decide(group: SuggestionGroup, decision: Decision) {
        viewModelScope.launch { decideGroup(c, group, decision) }
    }

    override fun editAndAccept(group: SuggestionGroup, text: String) {
        viewModelScope.launch { c.sessions.decide(group.ids, Decision.ACCEPTED, text.takeIf { it.trim() != group.lead.change }) }
    }

    override fun acceptAll(from: String?) {
        viewModelScope.launch { acceptAllIn(c, ui.value?.groups.orEmpty(), from) }
    }

    /** For reviews whose reply couldn't be parsed: the user types the suggestion in. */
    override fun addSuggestion(message: SessionMessage, target: SuggestionTarget, section: String, change: String) {
        viewModelScope.launch { c.sessions.addSuggestions(message.id, message.participantName, listOf(ParsedSuggestion(target, section.ifBlank { null }, change.trim(), null))) }
    }

    /** Suggestions not yet implemented, for "Accept all from…". */
    override fun backers(): List<String> = pendingBackers(ui.value?.groups.orEmpty())
}

/** Participants as offered for steps: their model, cost and whether they can run now. */
fun observeTeam(c: AppContainer): Flow<List<TeamMember>> =
    combine(c.participants.participants, c.backends.backends, c.backends.allModels, c.settings.freeOnly) { participants, backends, models, freeOnly ->
        val options = buildModelOptions(backends, models)
        participants.map { p ->
            val backend = p.model?.let { ref -> backends.firstOrNull { it.id == ref.backendId } }
            val option = options.firstOrNull { it.ref == p.model }
            val isFree = option?.isFree ?: backend?.isRelay
            TeamMember(
                participant = p,
                modelLabel = describeRef(p.model, options, backends),
                isFree = isFree,
                relayPackage = backend?.takeIf { it.isRelay }?.relayPackage,
                blockedReason = when {
                    p.model == null || backend == null -> "No model chosen"
                    freeOnly && isFree == false -> "Paid model; free-only mode is on"
                    else -> null
                },
                baseUrl = backend?.takeIf { !it.isRelay }?.baseUrl,
            )
        }
    }

/** A decision applies to every suggestion in the group; accepting keeps the user's rewording. */
suspend fun decideGroup(c: AppContainer, group: SuggestionGroup, decision: Decision) =
    c.sessions.decide(group.ids, decision, group.lead.editedChange.takeIf { decision == Decision.ACCEPTED })

suspend fun acceptAllIn(c: AppContainer, groups: List<SuggestionGroup>, from: String?) {
    val pending = groups.filter { it.decision == Decision.PENDING && (from == null || from in it.backers) }
    c.sessions.decide(pending.flatMap { it.ids }, Decision.ACCEPTED)
}

fun pendingBackers(groups: List<SuggestionGroup>): List<String> =
    groups.filter { it.decision == Decision.PENDING }.flatMap { it.backers }.distinct()
