package app.sunosongwriter.ui.song

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.ui.components.CostLabel
import app.sunosongwriter.ui.components.Tag

/** Who runs a step: a participant picker with their model, cost and why they can't run, if they can't. */
@Composable
internal fun ParticipantField(members: List<TeamMember>, selectedId: Long?, onPick: (Long) -> Unit) {
    val member = members.firstOrNull { it.participant.id == selectedId }
    Picker(
        "Participant",
        member?.let { "${it.participant.avatar} ${it.participant.name}" } ?: "Choose",
        members.map { m ->
            val note = m.blockedReason ?: if (m.isRelay) "relay" else null
            ("${m.participant.avatar} ${m.participant.name}" + (note?.let { " ($it)" } ?: "")) to m.participant.id
        },
        onPick,
    )
    member?.let { m ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(m.modelLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
            if (m.isRelay) Tag("Relay") else m.isFree?.let { CostLabel(it) }
        }
        m.blockedReason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (m.isRelay) Text("This makes a prompt for you to paste into ${m.modelLabel}; paste its reply back on the Team tab.", style = MaterialTheme.typography.bodySmall)
    }
}

/** What the user asked Quick start for. [artist] is only asked for by Quick generate (a new song). */
data class QuickStartRequest(val idea: String, val artist: String, val participantId: Long, val instructions: String)

/**
 * Quick start (a song) and Quick generate (a new song from the library): one model turns an idea into a
 * whole first draft that the team can then refine.
 */
@Composable
fun QuickStartDialog(
    members: List<TeamMember>,
    initialIdea: String,
    newSong: Boolean,
    replacesContent: Boolean,
    onDismiss: () -> Unit,
    onOpenParticipants: () -> Unit,
    onRun: (QuickStartRequest) -> Unit,
) {
    var idea by remember { mutableStateOf(initialIdea) }
    var artist by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Long?>(null) }
    val memberId = picked ?: suggestedMember(members, SessionAction.QUICK_START)
    val member = members.firstOrNull { it.participant.id == memberId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (newSong) "Quick generate" else "Quick start") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Describe the song in a line or two. One model writes a first draft: lyrics, style and settings" +
                        (if (newSong) ", plus a title and genre tags." else ", plus a title and genre tags if they're blank.") +
                        " Refine it with your team afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = idea,
                    onValueChange = { idea = it },
                    label = { Text("Idea") },
                    placeholder = { Text("e.g. pop-punk song about waiting for a call that never comes, rain on a tin roof") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (newSong) {
                    OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Artist (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (members.isEmpty()) {
                    Text("You need a team first: at least one participant with a model.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenParticipants) { Text("Set up participants") }
                } else {
                    ParticipantField(members, memberId) { picked = it }
                }
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Extra instructions (optional)") },
                    placeholder = { Text("e.g. female vocals, under three minutes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (replacesContent) {
                    Text("The draft becomes a new version; the current one stays in History.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { memberId?.let { onRun(QuickStartRequest(idea.trim(), artist.trim(), it, instructions.trim())) } },
                enabled = idea.isNotBlank() && member != null && member.blockedReason == null,
            ) { Text(if (newSong) "Generate" else "Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
