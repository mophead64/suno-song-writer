package app.sunosongwriter.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sunosongwriter.AppContainer
import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.domain.review.Decision
import app.sunosongwriter.domain.review.ParsedSuggestion
import app.sunosongwriter.domain.review.ReplyParser
import app.sunosongwriter.domain.review.SuggestionGroup
import app.sunosongwriter.domain.review.SuggestionGrouping
import app.sunosongwriter.domain.review.SuggestionTarget
import app.sunosongwriter.domain.workflow.MessageStatus
import app.sunosongwriter.domain.workflow.RoomConfig
import app.sunosongwriter.domain.workflow.RoomStage
import app.sunosongwriter.domain.workflow.RoomState
import app.sunosongwriter.domain.workflow.Session
import app.sunosongwriter.domain.workflow.SessionMessage
import app.sunosongwriter.ui.RoomRoute
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.Tag
import app.sunosongwriter.ui.song.AddSuggestionDialog
import app.sunosongwriter.ui.song.EditSuggestionDialog
import app.sunosongwriter.ui.song.MessageCard
import app.sunosongwriter.ui.song.RelayReplyDialog
import app.sunosongwriter.ui.song.StepActions
import app.sunosongwriter.ui.song.SuggestionsPanel
import app.sunosongwriter.ui.song.TeamMember
import app.sunosongwriter.ui.song.acceptAllIn
import app.sunosongwriter.ui.song.decideGroup
import app.sunosongwriter.ui.song.observeTeam
import app.sunosongwriter.ui.song.pendingBackers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

data class RoomUi(
    val session: Session,
    val config: RoomConfig,
    val state: RoomState,
    val songTitle: String,
    val messages: List<SessionMessage>,
    val groups: List<SuggestionGroup>,
    val acceptedCount: Int,
    val members: List<TeamMember>,
    /** Version id → "Consensus v4". */
    val versionLabels: Map<Long, String>,
    val versionNumbers: Map<Long, Int>,
    /** Variant id → its newest version id. */
    val latestVersionOf: Map<Long, Long>,
) {
    val tokens: Int get() = messages.sumOf { (it.inputTokens ?: 0) + (it.outputTokens ?: 0) }
    val costUsd: Double? get() = messages.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum()
    val running: Boolean get() = messages.any { it.status.isActive }
    val waitingRelay: Boolean get() = messages.any { it.status == MessageStatus.WAITING_RELAY && it.id in state.stepIds }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoomViewModel(savedState: SavedStateHandle, private val c: AppContainer) : ViewModel(), StepActions {
    private val sessionId = savedState.toRoute<RoomRoute>().sessionId

    val ui = c.sessions.observe(sessionId).filterNotNull().flatMapLatest { session ->
        combine(
            c.sessions.observeMessages(session.id),
            c.sessions.observeSuggestions(session.id),
            observeTeam(c),
            c.songs.observeSong(session.songId),
            combine(c.songs.observeVersions(session.songId), c.songs.observeVariants(session.songId)) { v, r -> v to r },
        ) { messages, suggestions, members, song, (versions, variants) ->
            val state = c.room.state(session)
            val reviewIds = messages.filter { it.action == SessionAction.REVIEW && it.round == state.round }.map { it.id }.toSet()
            val current = suggestions.filter { it.messageId in reviewIds && it.implementedInVersionId == null }
            val variantNames = variants.associate { it.id to it.name }
            RoomUi(
                session = session,
                config = c.room.config(session),
                state = state,
                songTitle = song?.title?.ifBlank { "Untitled" } ?: "",
                messages = messages,
                groups = SuggestionGrouping.group(current),
                acceptedCount = current.count { it.decision == Decision.ACCEPTED },
                members = members,
                versionLabels = versions.associate { it.id to "${variantNames[it.variantId] ?: "?"} v${it.number}" },
                versionNumbers = versions.associate { it.id to it.number },
                latestVersionOf = versions.groupBy { it.variantId }.mapValues { (_, vs) -> vs.maxBy { it.number }.id },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun chair(action: suspend () -> Unit) {
        viewModelScope.launch {
            action()
            c.roomScheduler.kick(sessionId)
        }
    }

    fun pickStyle(messageId: Long?) = chair { c.room.pickStyle(sessionId, messageId) }
    fun implement() = chair { c.room.implementAccepted(sessionId) }
    fun finish() = chair { c.room.finishDebate(sessionId) }
    fun resume() = chair { c.room.resume(sessionId) }
    fun stopRoom() = viewModelScope.launch {
        c.room.stop(sessionId)
        c.roomScheduler.cancel(sessionId)
        ui.value?.messages?.filter { it.status.isActive }?.forEach { c.runner.cancel(it.id) }
        c.roomNotifier.clear(sessionId)
    }

    override fun retry(messageId: Long) { viewModelScope.launch { c.runner.retry(messageId) } }
    override fun stop(messageId: Long) = c.runner.cancel(messageId)
    override fun delete(messageId: Long) {}
    override fun skip(messageId: Long) { viewModelScope.launch { c.runner.skip(messageId) } }
    override fun submitRelay(messageId: Long, text: String) { viewModelScope.launch { c.runner.submitRelayReply(messageId, text) } }
    override fun addSuggestion(message: SessionMessage, target: SuggestionTarget, section: String, change: String) {
        viewModelScope.launch { c.sessions.addSuggestions(message.id, message.participantName, listOf(ParsedSuggestion(target, section.ifBlank { null }, change.trim(), null))) }
    }
    override fun decide(group: SuggestionGroup, decision: Decision) { viewModelScope.launch { decideGroup(c, group, decision) } }
    override fun editAndAccept(group: SuggestionGroup, text: String) {
        viewModelScope.launch { c.sessions.decide(group.ids, Decision.ACCEPTED, text.takeIf { it.trim() != group.lead.change }) }
    }
    override fun acceptAll(from: String?) { viewModelScope.launch { acceptAllIn(c, ui.value?.groups.orEmpty(), from) } }
    override fun backers(): List<String> = pendingBackers(ui.value?.groups.orEmpty())
}

private sealed interface RoomDialog {
    data class Relay(val message: SessionMessage) : RoomDialog
    data class Edit(val group: SuggestionGroup) : RoomDialog
    data class Add(val message: SessionMessage) : RoomDialog
    data object ConfirmStop : RoomDialog
}

/** The Writers' Room as a group chat (spec 5.5), with the chair's controls at the bottom. */
@Composable
fun RoomScreen(
    onBack: () -> Unit,
    onOpenSong: (Long) -> Unit,
    onCompare: (songId: Long, fromId: Long, toId: Long) -> Unit,
    onHandoff: (songId: Long, versionId: Long) -> Unit,
) {
    val vm = appViewModel { c, saved -> RoomViewModel(saved, c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<RoomDialog?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(ui?.messages?.size, ui?.state?.stage) { ui?.let { list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) } }

    Scaffold(
        topBar = { BackTopBar("Writers' Room · ${ui?.songTitle.orEmpty()}", onBack) },
        bottomBar = { ui?.let { ChairBar(it, vm, onOpenSong, onCompare, onHandoff, onStop = { dialog = RoomDialog.ConfirmStop }) } },
    ) { padding ->
        val room = ui ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { StatusHeader(room) }
            var lastMarker: String? = null
            room.messages.forEach { m ->
                val marker = marker(room, m)
                if (marker != lastMarker) {
                    lastMarker = marker
                    item(key = "marker-${m.id}") { RoundMarker(marker) }
                }
                item(key = m.id) {
                    val isPickable = room.state.stage == RoomStage.STYLE_PICK && m.proposal && m.status == MessageStatus.DONE
                    MessageCard(
                        m = m,
                        member = room.members.firstOrNull { it.participant.id == m.participantId },
                        producedNumber = m.producedVersionId?.let(room.versionNumbers::get),
                        actions = vm,
                        onCompare = { from, to -> onCompare(room.session.songId, from, to) },
                        onRelayReply = { dialog = RoomDialog.Relay(m) },
                        onAddSuggestion = { dialog = RoomDialog.Add(m) },
                        canSkip = m.id in room.state.stepIds && !room.state.stage.isOver,
                        canDelete = false,
                        extra = { if (m.proposal) ProposalPreview(m, isPickable, onPick = { vm.pickStyle(m.id) }) },
                    )
                }
            }
            if (room.state.stage == RoomStage.REVIEW_DECISION && room.groups.isNotEmpty()) {
                item(key = "suggestions") {
                    SuggestionsPanel(room.groups, room.acceptedCount, vm, onEdit = { dialog = RoomDialog.Edit(it) }, onImplement = null)
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is RoomDialog.Relay -> RelayReplyDialog(d.message, onDismiss = { dialog = null }) { text -> vm.submitRelay(d.message.id, text); dialog = null }
        is RoomDialog.Edit -> EditSuggestionDialog(d.group, onDismiss = { dialog = null }) { text -> vm.editAndAccept(d.group, text); dialog = null }
        is RoomDialog.Add -> AddSuggestionDialog(onDismiss = { dialog = null }) { target, section, change -> vm.addSuggestion(d.message, target, section, change); dialog = null }
        RoomDialog.ConfirmStop -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Stop the Writers' Room?") },
            text = { Text("Steps in progress are cancelled. Versions already saved are kept.") },
            confirmButton = { TextButton(onClick = { vm.stopRoom(); dialog = null }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Keep going") } },
        )
    }
}

/** Groups the chat into phases: "Lyrics", "Style proposals", "Round 2 · from Consensus v5", "Finalise". */
private fun marker(room: RoomUi, m: SessionMessage): String {
    val from = m.basedOnVersionId?.let(room.versionLabels::get)?.let { " · from $it" }.orEmpty()
    return when {
        m.action == SessionAction.WRITE_LYRICS -> "Lyrics$from"
        m.proposal -> "Style proposals$from"
        m.action == SessionAction.FINALISE -> "Finalise$from"
        else -> "Round ${m.round}$from"
    }
}

@Composable
private fun RoundMarker(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(Modifier.weight(1f))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusHeader(room: RoomUi) {
    val state = room.state
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Tag(state.pause?.let { "Paused" } ?: state.stage.label)
                if (state.stage !in setOf(RoomStage.LYRICS, RoomStage.STYLE_PROPOSALS, RoomStage.STYLE_PICK)) Tag("Round ${state.round} of ${room.config.roundCap}")
                if (room.waitingRelay) Tag("Waiting for you to relay")
            }
            state.pause?.let { p ->
                Text(
                    p.resumeAtMillis?.let { "Resumes at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}. ${p.reason}" } ?: p.reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            state.endedBecause?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val cost = room.costUsd?.let { " · \$" + "%.4f".format(it) } ?: ""
            Text("${room.tokens} tokens so far$cost", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun ProposalPreview(m: SessionMessage, pickable: Boolean, onPick: () -> Unit) {
    val style = remember(m.content) { ReplyParser.parse(m.content, SongContent(), SessionAction.WRITE_STYLE)?.content?.style?.flatText() }
    if (m.chosen) Tag("Picked", container = MaterialTheme.colorScheme.secondaryContainer)
    style?.let {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.small) {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
        }
    }
    if (pickable) Button(onClick = onPick) { Text("Use this style") }
}

/** What the chair can do right now (spec 5.5: manual chair). */
@Composable
private fun ChairBar(
    room: RoomUi,
    vm: RoomViewModel,
    onOpenSong: (Long) -> Unit,
    onCompare: (Long, Long, Long) -> Unit,
    onHandoff: (Long, Long) -> Unit,
    onStop: () -> Unit,
) {
    val state = room.state
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                state.stage.isOver -> {
                    Text(if (state.stage == RoomStage.DONE) "Done. Send each variant to Suno:" else "Stopped.", style = MaterialTheme.typography.titleSmall)
                    val consensus = room.latestVersionOf[state.consensusVariantId]
                    val refined = state.refinedVariantId?.let(room.latestVersionOf::get)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        consensus?.let { Button(onClick = { onHandoff(room.session.songId, it) }) { Text("Consensus") } }
                        refined?.let { Button(onClick = { onHandoff(room.session.songId, it) }) { Text("Framework-refined") } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onOpenSong(room.session.songId) }) { Text("Open the song") }
                        if (consensus != null && refined != null) {
                            OutlinedButton(onClick = { onCompare(room.session.songId, consensus, refined) }) { Text("Compare") }
                        }
                    }
                }
                state.pause != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::resume) { Text("Resume now") }
                    OutlinedButton(onClick = vm::finish) { Text("Finish debate") }
                    TextButton(onClick = onStop) { Text("Stop") }
                }
                state.stage == RoomStage.STYLE_PICK -> {
                    Text("Pick one of the proposed styles above, or keep the current one.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { vm.pickStyle(null) }) { Text("Keep the current style") }
                }
                state.stage == RoomStage.REVIEW_DECISION -> {
                    Text(
                        if (room.groups.isEmpty()) "No suggestions this round." else "Accept the suggestions you want, then implement them.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::implement, enabled = room.acceptedCount > 0) { Text("Implement ${room.acceptedCount}") }
                        OutlinedButton(onClick = vm::finish) { Text("Finish debate") }
                    }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (room.waitingRelay && !room.running) "Waiting for your relay replies." else "${state.stage.label}…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.stage == RoomStage.REVIEW || state.stage == RoomStage.IMPLEMENT) TextButton(onClick = vm::finish) { Text("Finish debate") }
                    TextButton(onClick = onStop) { Text("Stop") }
                }
            }
        }
    }
}
