package app.sunosongwriter.ui.share

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.AppContainer
import app.sunosongwriter.domain.workflow.WaitingRelay
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WaitingRow(val waiting: WaitingRelay, val songTitle: String)

data class ShareUi(val text: String, val waiting: List<WaitingRow>)

/**
 * Text shared into the app (spec 5.16). If a relay step is waiting, the text is offered to it first
 * as the chat app's reply (spec 5.2); otherwise it can start a new song. Appending to an existing
 * song's concept arrives with the full quick-capture flow in iteration 7.
 */
class ShareViewModel(private val c: AppContainer) : ViewModel() {
    val ui = combine(c.pendingShare, c.sessions.observeWaitingRelay(), c.songs.songs) { text, waiting, songs ->
        text?.let { ShareUi(it, waiting.map { w -> WaitingRow(w, songs.firstOrNull { it.id == w.songId }?.title?.ifBlank { "Untitled" } ?: "Song") }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Submits the reply, then returns to where it belongs: the Writers' Room, or the song's Team tab. */
    fun useAsReply(row: WaitingRow, openSong: (Long) -> Unit, openRoom: (Long) -> Unit) = viewModelScope.launch {
        val text = c.pendingShare.value ?: return@launch
        c.pendingShare.value = null
        c.runner.submitRelayReply(row.waiting.message.id, text)
        if (row.waiting.inRoom) openRoom(row.waiting.message.sessionId) else openSong(row.waiting.songId)
    }

    fun newSong(then: (songId: Long) -> Unit) = viewModelScope.launch {
        val text = c.pendingShare.value ?: return@launch
        c.pendingShare.value = null
        val title = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: "Shared idea"
        then(c.songs.createSong(title, "", text.trim(), emptyList(), null))
    }

    fun dismiss() {
        c.pendingShare.value = null
    }
}

@Composable
fun ShareScreen(onDone: () -> Unit, onOpenSong: (Long) -> Unit, onOpenRoom: (Long) -> Unit, onEditNewSong: (Long) -> Unit) {
    val vm = appViewModel { c, _ -> ShareViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(topBar = { BackTopBar("Shared text", onBack = { vm.dismiss(); onDone() }) }) { padding ->
        val state = ui ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Box(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    Text(state.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
            if (state.waiting.isNotEmpty()) {
                Text("Use as a relay reply", style = MaterialTheme.typography.titleMedium)
                state.waiting.forEach { row ->
                    val m = row.waiting.message
                    val ago = DateUtils.getRelativeTimeSpanString(m.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    Button(onClick = { vm.useAsReply(row, onOpenSong, onOpenRoom) }, modifier = Modifier.fillMaxWidth()) {
                        val where = if (row.waiting.inRoom) " in the Writers' Room" else ""
                        Text("${m.participantName}'s ${m.action.label.lowercase()} for \"${row.songTitle}\"$where ($ago)")
                    }
                }
            }
            Text("Or", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { vm.newSong(onEditNewSong) }, modifier = Modifier.fillMaxWidth()) { Text("Start a new song with this as its concept") }
        }
    }
}
