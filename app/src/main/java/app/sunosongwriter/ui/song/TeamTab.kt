package app.sunosongwriter.ui.song

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sunosongwriter.domain.model.Role
import app.sunosongwriter.domain.model.Version
import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.domain.prompt.TemplateKey
import app.sunosongwriter.domain.review.Decision
import app.sunosongwriter.domain.review.SuggestionGroup
import app.sunosongwriter.domain.review.SuggestionTarget
import app.sunosongwriter.domain.review.Verdict
import app.sunosongwriter.domain.workflow.MessageStatus
import app.sunosongwriter.domain.workflow.SessionMessage
import app.sunosongwriter.ui.components.CostLabel
import app.sunosongwriter.ui.components.RelayLauncher
import app.sunosongwriter.ui.components.Tag
import app.sunosongwriter.ui.components.TagRow
import app.sunosongwriter.ui.participants.Avatar

/** What message cards and the suggestion panel can do; implemented by the Team tab and the Writers' Room. */
interface StepActions {
    fun retry(messageId: Long)
    fun stop(messageId: Long)
    fun delete(messageId: Long)
    fun skip(messageId: Long) {}
    fun submitRelay(messageId: Long, text: String)
    fun addSuggestion(message: SessionMessage, target: SuggestionTarget, section: String, change: String)
    fun decide(group: SuggestionGroup, decision: Decision)
    fun editAndAccept(group: SuggestionGroup, text: String)
    fun acceptAll(from: String? = null)
    fun backers(): List<String>
}

private sealed interface TeamDialog {
    data class Run(val action: SessionAction? = null) : TeamDialog
    data class RelayReply(val message: SessionMessage) : TeamDialog
    data class EditSuggestion(val group: SuggestionGroup) : TeamDialog
    data class AddSuggestion(val message: SessionMessage) : TeamDialog
}

/** Manual mode (spec 5.4): run steps, decide on suggestions, relay to chat apps. */
@Composable
fun TeamTab(
    ui: TeamUi,
    vm: TeamViewModel,
    versions: List<Version>,
    onCompare: (fromId: Long, toId: Long) -> Unit,
    onOpenParticipants: () -> Unit,
    onStartRoom: () -> Unit,
    onOpenRoom: (Long) -> Unit,
) {
    var dialog by remember { mutableStateOf<TeamDialog?>(null) }

    if (ui.members.isEmpty()) {
        Text("Your team is empty. Add participants (a model with a name and role) in Settings → Participants.")
        Button(onClick = onOpenParticipants) { Text("Add participants") }
        return
    }

    RoomCard(ui.rooms, onStartRoom, onOpenRoom)
    Text("Manual steps", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { dialog = TeamDialog.Run() }) { Text("Run a step") }
        Text("Full transcript", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
        Switch(checked = ui.session?.fullTranscript == true, onCheckedChange = vm::setFullTranscript)
    }
    Text(
        if (ui.session?.fullTranscript == true) "Each step also gets earlier steps' prompts and replies (more context, more tokens)."
        else "Each step gets the current version only, which keeps requests small on free tiers.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (ui.groups.isNotEmpty()) {
        SuggestionsPanel(ui.groups, ui.acceptedCount, vm, onEdit = { dialog = TeamDialog.EditSuggestion(it) }, onImplement = { dialog = TeamDialog.Run(SessionAction.IMPLEMENT) })
    }

    if (ui.messages.isEmpty()) {
        Text(
            "No steps yet. Try \"Write lyrics\" with your lyricist, then \"Review\" with a couple of others.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val numbers = versions.associate { it.id to it.number }
    ui.messages.forEach { m ->
        MessageCard(
            m,
            member = ui.members.firstOrNull { it.participant.id == m.participantId },
            producedNumber = m.producedVersionId?.let(numbers::get),
            actions = vm,
            onCompare = onCompare,
            onRelayReply = { dialog = TeamDialog.RelayReply(m) },
            onAddSuggestion = { dialog = TeamDialog.AddSuggestion(m) },
        )
    }

    when (val d = dialog) {
        null -> Unit
        is TeamDialog.Run -> RunDialog(ui, d.action, onDismiss = { dialog = null }) { action, participantId, key, instructions ->
            vm.run(action, participantId, key, instructions)
            dialog = null
        }
        is TeamDialog.RelayReply -> RelayReplyDialog(d.message, onDismiss = { dialog = null }) { text -> vm.submitRelay(d.message.id, text); dialog = null }
        is TeamDialog.EditSuggestion -> EditSuggestionDialog(d.group, onDismiss = { dialog = null }) { text -> vm.editAndAccept(d.group, text); dialog = null }
        is TeamDialog.AddSuggestion -> AddSuggestionDialog(onDismiss = { dialog = null }) { target, section, change ->
            vm.addSuggestion(d.message, target, section, change)
            dialog = null
        }
    }
}

/** The Writers' Room: the automated run of spec section 2. */
@Composable
private fun RoomCard(rooms: List<RoomSummary>, onStart: () -> Unit, onOpen: (Long) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Writers' Room", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your whole team writes, proposes styles and reviews in rounds, while you chair. It produces a Consensus variant and a Framework-refined one.",
                style = MaterialTheme.typography.bodySmall,
            )
            rooms.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onOpen(r.sessionId) }) {
                    Text(r.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { onOpen(r.sessionId) }) { Text("Open") }
                }
            }
            if (rooms.none { it.active }) FilledTonalButton(onClick = onStart) { Text("Start a Writers' Room") }
        }
    }
}

// Suggestions

@Composable
internal fun SuggestionsPanel(
    groups: List<SuggestionGroup>,
    acceptedCount: Int,
    actions: StepActions,
    onEdit: (SuggestionGroup) -> Unit,
    onImplement: (() -> Unit)?,
) {
    var fromMenu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Suggestions this round", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { actions.acceptAll() }) { Text("Accept all") }
                Box {
                    TextButton(onClick = { fromMenu = true }) {
                        Text("Accept all from")
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = fromMenu, onDismissRequest = { fromMenu = false }) {
                        actions.backers().forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { fromMenu = false; actions.acceptAll(name) }) }
                    }
                }
            }
            groups.forEach { SuggestionCard(it, actions, onEdit) }
            if (onImplement != null) {
                FilledTonalButton(onClick = onImplement, enabled = acceptedCount > 0, modifier = Modifier.fillMaxWidth()) {
                    Text(if (acceptedCount == 0) "Accept suggestions to implement them" else "Implement $acceptedCount accepted")
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(group: SuggestionGroup, actions: StepActions, onEdit: (SuggestionGroup) -> Unit) {
    val s = group.lead
    val faded = group.decision == Decision.REJECTED
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TagRow {
                Tag(listOfNotNull(s.target.label, s.section).joinToString(" · "))
                when (group.decision) {
                    Decision.ACCEPTED -> Tag("Accepted", container = MaterialTheme.colorScheme.secondaryContainer)
                    Decision.REJECTED -> Tag("Rejected")
                    Decision.PENDING -> Unit
                }
            }
            Text(
                s.effectiveChange,
                style = MaterialTheme.typography.bodyMedium,
                color = if (faded) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
            if (s.editedChange != null) Text("Edited from: ${s.change}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.rationale?.let { Text("Why: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                if (group.backers.size > 1) "Backed by ${group.backers.size}: ${group.backers.joinToString()}" else "From ${group.backers.single()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row {
                IconButton(onClick = { actions.decide(group, if (group.decision == Decision.ACCEPTED) Decision.PENDING else Decision.ACCEPTED) }) {
                    Icon(Icons.Filled.Check, if (group.decision == Decision.ACCEPTED) "Undo accept" else "Accept")
                }
                IconButton(onClick = { actions.decide(group, if (group.decision == Decision.REJECTED) Decision.PENDING else Decision.REJECTED) }) {
                    Icon(Icons.Filled.Close, if (group.decision == Decision.REJECTED) "Undo reject" else "Reject")
                }
                IconButton(onClick = { onEdit(group) }) { Icon(Icons.Filled.Edit, "Edit, then accept") }
            }
        }
    }
}

// Messages

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MessageCard(
    m: SessionMessage,
    member: TeamMember?,
    producedNumber: Int?,
    actions: StepActions,
    onCompare: (Long, Long) -> Unit,
    onRelayReply: () -> Unit,
    onAddSuggestion: () -> Unit,
    canSkip: Boolean = false,
    /** Writers' Room steps can't be deleted: the room's state refers to them. */
    canDelete: Boolean = true,
    extra: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    var showPrompt by remember { mutableStateOf(false) }
    // Replies are open while a step is running and fold away once it's done.
    var showReply by remember(m.status == MessageStatus.DONE) { mutableStateOf(m.status != MessageStatus.DONE) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(m.participantAvatar)
                Column(Modifier.weight(1f)) {
                    Text("${m.participantName} · ${m.action.label}", style = MaterialTheme.typography.titleSmall)
                    m.modelLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (m.status.isActive) CircularProgressIndicator(Modifier.size(20.dp))
                if (m.status != MessageStatus.DONE) Tag(m.status.label)
            }
            m.instructions?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall) }

            // Outcome
            when {
                m.status == MessageStatus.DONE && m.action == SessionAction.REVIEW && !m.parseFailed -> {
                    Text(
                        (if (m.verdict == Verdict.APPROVE) "Approves. " else "Suggests changes. ") + (m.summary ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                m.status == MessageStatus.DONE && m.action == SessionAction.REVIEW && m.parseFailed -> {
                    Text("Couldn't read this review as JSON. Read it below and add suggestions by hand.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onAddSuggestion) { Text("Add a suggestion") }
                }
                m.status == MessageStatus.DONE && producedNumber != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Saved as v$producedNumber", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onCompare(m.basedOnVersionId ?: 0, m.producedVersionId!!) }) { Text("See changes") }
                    }
                }
                m.status == MessageStatus.DONE -> m.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                m.status == MessageStatus.FAILED || m.status == MessageStatus.RATE_LIMITED -> m.error?.let {
                    Text(it, color = if (m.status == MessageStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                else -> Unit
            }

            m.warning?.let {
                Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }

            // Relay controls (spec 5.2)
            if (m.status == MessageStatus.WAITING_RELAY) {
                val app = m.modelLabel ?: "the chat app"
                Text("Copy the prompt into $app, then bring the reply back by pasting it here or sharing it to Song Writer.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pkg = member?.relayPackage
                    Button(onClick = {
                        if (pkg.isNullOrBlank()) {
                            RelayLauncher.copy(context, "Prompt", m.prompt)
                            Toast.makeText(context, "Prompt copied", Toast.LENGTH_SHORT).show()
                        } else if (RelayLauncher.copyAndOpen(context, pkg, m.prompt) == RelayLauncher.Result.NOT_INSTALLED) {
                            Toast.makeText(context, "Prompt copied. $app isn't installed.", Toast.LENGTH_LONG).show()
                        }
                    }) { Text(if (pkg.isNullOrBlank()) "Copy prompt" else "Copy & open") }
                    if (!pkg.isNullOrBlank()) OutlinedButton(onClick = { RelayLauncher.share(context, pkg, m.prompt) }) { Text("Share to app") }
                    OutlinedButton(onClick = onRelayReply) { Text("Paste reply") }
                }
            }

            // Actions
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (m.status.isActive) TextButton(onClick = { actions.stop(m.id) }) { Text("Stop") }
                if (m.status == MessageStatus.FAILED && member?.isRelay == false) TextButton(onClick = { actions.retry(m.id) }) { Text("Retry") }
                if (canSkip && (m.status == MessageStatus.FAILED || m.status == MessageStatus.WAITING_RELAY)) TextButton(onClick = { actions.skip(m.id) }) { Text("Skip") }
                if (m.content.isNotBlank()) TextButton(onClick = { showReply = !showReply }) { Text(if (showReply) "Hide reply" else "Show reply") }
                TextButton(onClick = { showPrompt = !showPrompt }) { Text(if (showPrompt) "Hide prompt" else "Show prompt") }
                if (!m.status.isActive && canDelete) TextButton(onClick = { actions.delete(m.id) }) { Text("Delete") }
            }
            if (showReply && m.content.isNotBlank()) {
                Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(m.content, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (showPrompt) {
                Box(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(m.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            extra()
            if (m.inputTokens != null || m.outputTokens != null) {
                Text("${m.inputTokens ?: "?"} tokens in · ${m.outputTokens ?: "?"} out", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Dialogs

@Composable
private fun RunDialog(
    ui: TeamUi,
    initialAction: SessionAction?,
    onDismiss: () -> Unit,
    onRun: (SessionAction, Long, TemplateKey?, String) -> Unit,
) {
    val actions = SessionAction.entries.filter { it.runnable }
    var action by remember { mutableStateOf(initialAction ?: SessionAction.WRITE_LYRICS) }
    var pickedMember by remember { mutableStateOf<Long?>(null) }
    // Until the user picks someone, suggest the participant whose role fits the action.
    val memberId = pickedMember ?: suggestedMember(ui.members, action)
    var templateKey by remember { mutableStateOf<TemplateKey?>(null) }
    var instructions by remember { mutableStateOf("") }
    val member = ui.members.firstOrNull { it.participant.id == memberId }
    val templates = ui.templates.filter { it.action == action }
    val canRun = member != null && member.blockedReason == null && (!action.needsInstructions || instructions.isNotBlank()) &&
        (action != SessionAction.IMPLEMENT || ui.acceptedCount > 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run a step") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Picker("Action", action.label, actions.map { it.label to it }) { action = it; templateKey = null }
                Picker(
                    "Participant",
                    member?.let { "${it.participant.avatar} ${it.participant.name}" } ?: "Choose",
                    ui.members.map { m ->
                        val note = m.blockedReason ?: if (m.isRelay) "relay" else null
                        ("${m.participant.avatar} ${m.participant.name}" + (note?.let { " ($it)" } ?: "")) to m.participant.id
                    },
                ) { pickedMember = it }
                member?.let { m ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.modelLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
                        if (m.isRelay) Tag("Relay") else m.isFree?.let { CostLabel(it) }
                    }
                    m.blockedReason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                Picker(
                    "Template",
                    templates.firstOrNull { it.key == templateKey }?.name ?: "Default for this participant",
                    listOf<Pair<String, TemplateKey?>>("Default for this participant" to null) + templates.map { it.name to it.key },
                ) { templateKey = it }
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text(if (action.needsInstructions) "What should change?" else "Extra instructions (optional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (action == SessionAction.IMPLEMENT) {
                    Text("Applies the ${ui.acceptedCount} accepted suggestions, and nothing else.", style = MaterialTheme.typography.bodySmall)
                }
                if (member?.isRelay == true) {
                    Text("This creates a ready-made prompt for you to paste into ${member.modelLabel}.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRun(action, memberId!!, templateKey, instructions) }, enabled = canRun) { Text("Run") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun suggestedMember(members: List<TeamMember>, action: SessionAction): Long? {
    val role = when (action) {
        SessionAction.WRITE_LYRICS -> Role.LYRICIST
        SessionAction.WRITE_STYLE -> Role.STYLIST
        SessionAction.REVIEW -> Role.REVIEWER
        SessionAction.IMPLEMENT, SessionAction.TWEAK, SessionAction.FIX -> Role.IMPLEMENTER
        SessionAction.FINALISE -> Role.FINALISER
    }
    val available = members.filter { it.blockedReason == null }
    return (available.firstOrNull { it.participant.defaultRole == role } ?: available.firstOrNull { !it.isRelay } ?: available.firstOrNull())?.participant?.id
}

@Composable
internal fun <T> Picker(label: String, current: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (text, value) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) }) }
            }
        }
    }
}

@Composable
internal fun RelayReplyDialog(message: SessionMessage, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reply from ${message.participantName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { RelayLauncher.clipboardText(context)?.let { text = it } }) { Text("Paste from clipboard") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Paste the whole reply") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) { Text("Use reply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun EditSuggestionDialog(group: SuggestionGroup, onDismiss: () -> Unit, onAccept: (String) -> Unit) {
    var text by remember { mutableStateOf(group.lead.effectiveChange) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit, then accept") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onAccept(text) }, enabled = text.isNotBlank()) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun AddSuggestionDialog(onDismiss: () -> Unit, onAdd: (SuggestionTarget, String, String) -> Unit) {
    var target by remember { mutableStateOf(SuggestionTarget.LYRICS) }
    var section by remember { mutableStateOf("") }
    var change by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a suggestion") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker("Applies to", target.label, SuggestionTarget.entries.map { it.label to it }) { target = it }
                OutlinedTextField(value = section, onValueChange = { section = it }, label = { Text("Section (optional)") }, placeholder = { Text("e.g. Chorus") }, singleLine = true)
                OutlinedTextField(value = change, onValueChange = { change = it }, label = { Text("Change") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(target, section, change) }, enabled = change.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
