package app.sunosongwriter.ui.share

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.AppContainer
import app.sunosongwriter.domain.model.QuickCapture
import app.sunosongwriter.domain.model.Snapshot
import app.sunosongwriter.domain.model.Song
import app.sunosongwriter.domain.workflow.WaitingRelay
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.song.QuickStartDialog
import app.sunosongwriter.ui.song.QuickStartRequest
import app.sunosongwriter.ui.song.TeamMember
import app.sunosongwriter.ui.song.observeTeam
import app.sunosongwriter.ui.song.quickGenerate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WaitingRow(val waiting: WaitingRelay, val songTitle: String)

/** A sent snapshot a shared Suno link could be logged against. */
data class SentRow(val snapshot: Snapshot, val songTitle: String)

data class ShareUi(
    val text: String,
    val waiting: List<WaitingRow>,
    val sunoLinks: List<String>,
    /** Most recently sent first. */
    val recentlySent: List<SentRow>,
    /** Most recently edited first. */
    val songs: List<Song>,
    val members: List<TeamMember>,
)

/**
 * Quick capture (spec 5.16). Shared text is offered, in order, as a waiting relay step's reply (5.2),
 * as a Suno take for something recently sent (when it holds a Suno link), as a new song, or added to an
 * existing song's concept.
 */
class ShareViewModel(private val c: AppContainer) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    val ui = combine(
        c.pendingShare,
        c.sessions.observeWaitingRelay(),
        c.songs.songs,
        c.snapshots.all,
        observeTeam(c),
    ) { text, waiting, songs, snapshots, members ->
        text ?: return@combine null
        fun title(id: Long) = songs.firstOrNull { it.id == id }?.title?.ifBlank { "Untitled" } ?: "Song"
        ShareUi(
            text = text,
            waiting = waiting.map { w -> WaitingRow(w, title(w.songId)) },
            sunoLinks = QuickCapture.sunoLinks(text),
            recentlySent = snapshots.sortedByDescending { it.sentAt }.take(8).map { SentRow(it, title(it.songId)) },
            songs = songs,
            members = members,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Submits the reply, then returns to where it belongs: the Writers' Room, or the song's Team tab. */
    fun useAsReply(row: WaitingRow, openSong: (Long) -> Unit, openRoom: (Long) -> Unit) = viewModelScope.launch {
        val text = take() ?: return@launch
        c.runner.submitRelayReply(row.waiting.message.id, text)
        if (row.waiting.inRoom) openRoom(row.waiting.message.sessionId) else openSong(row.waiting.songId)
    }

    /** Logs each shared Suno link as a take on [row]'s snapshot, then opens its results to rate them. */
    fun logTakes(row: SentRow, openResults: (songId: Long, snapshotId: Long) -> Unit) = viewModelScope.launch {
        val text = take() ?: return@launch
        QuickCapture.sunoLinks(text).forEach { c.results.addTake(row.snapshot.id, it, null, "", emptyList()) }
        openResults(row.snapshot.songId, row.snapshot.id)
    }

    fun newSong(then: (songId: Long) -> Unit) = viewModelScope.launch {
        val text = take() ?: return@launch
        then(c.songs.createSong(QuickCapture.titleFrom(text), "", text.trim(), emptyList(), null))
    }

    fun quickGenerate(request: QuickStartRequest, then: (songId: Long) -> Unit) = viewModelScope.launch {
        val result = quickGenerate(c, request)
        val id = result.songId
        if (id == null) {
            _message.value = result.error
        } else {
            c.pendingShare.value = null
            then(id)
        }
    }

    fun appendToConcept(song: Song, then: (songId: Long) -> Unit) = viewModelScope.launch {
        val text = take() ?: return@launch
        val current = c.songs.observeSong(song.id).first() ?: return@launch
        c.songs.updateSong(current.copy(concept = QuickCapture.appendToConcept(current.concept, text)))
        then(song.id)
    }

    fun dismiss() {
        c.pendingShare.value = null
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun take(): String? = c.pendingShare.value?.also { c.pendingShare.value = null }
}

@Composable
fun ShareScreen(
    onDone: () -> Unit,
    onOpenSong: (Long) -> Unit,
    onOpenRoom: (Long) -> Unit,
    onEditNewSong: (Long) -> Unit,
    onOpenResults: (songId: Long, snapshotId: Long) -> Unit,
    onOpenNewSong: (Long) -> Unit,
    onOpenParticipants: () -> Unit,
) {
    val vm = appViewModel { c, _ -> ShareViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var quickGenerate by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(topBar = { BackTopBar("Shared text", onBack = { vm.dismiss(); onDone() }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val state = ui ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Box(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    Text(state.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
            if (state.waiting.isNotEmpty()) {
                Heading("Use as a relay reply")
                state.waiting.forEach { row ->
                    val m = row.waiting.message
                    val ago = DateUtils.getRelativeTimeSpanString(m.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    Button(onClick = { vm.useAsReply(row, onOpenSong, onOpenRoom) }, modifier = Modifier.fillMaxWidth()) {
                        val where = if (row.waiting.inRoom) " in the Writers' Room" else ""
                        Text("${m.participantName}'s ${m.action.label.lowercase()} for \"${row.songTitle}\"$where ($ago)")
                    }
                }
            }
            if (state.sunoLinks.isNotEmpty() && state.recentlySent.isNotEmpty()) {
                Heading("Log as a Suno take")
                Text(
                    "${state.sunoLinks.size} Suno link${if (state.sunoLinks.size > 1) "s" else ""}. Which generation ${if (state.sunoLinks.size > 1) "are they" else "is it"} from?",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.recentlySent.forEach { row ->
                    val ago = DateUtils.getRelativeTimeSpanString(row.snapshot.sentAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    OutlinedButton(onClick = { vm.logTakes(row, onOpenResults) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${row.songTitle} · ${row.snapshot.versionLabel} (sent $ago)")
                    }
                }
            }

            Heading("Start a new song")
            OutlinedButton(onClick = { vm.newSong(onEditNewSong) }, modifier = Modifier.fillMaxWidth()) { Text("New song with this as its concept") }
            OutlinedButton(onClick = { quickGenerate = true }, modifier = Modifier.fillMaxWidth()) { Text("Quick generate a draft from it") }

            if (state.songs.isNotEmpty()) {
                Heading("Add to a song's concept")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Find a song") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val matches = state.songs.filter { s ->
                    query.isBlank() || s.title.contains(query, ignoreCase = true) || s.artist.contains(query, ignoreCase = true)
                }
                matches.take(10).forEach { song ->
                    ListItem(
                        headlineContent = { Text(song.title.ifBlank { "Untitled" }) },
                        supportingContent = { Text(listOf(song.artist, song.concept.lineSequence().firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1) },
                        modifier = Modifier.clickable { vm.appendToConcept(song, onOpenSong) },
                    )
                    HorizontalDivider()
                }
                if (matches.isEmpty()) Text("No songs match.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (matches.size > 10) Text("Showing 10 of ${matches.size}; type to narrow it down.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    val state = ui
    if (quickGenerate && state != null) {
        QuickStartDialog(
            members = state.members,
            initialIdea = state.text.trim(),
            newSong = true,
            replacesContent = false,
            onDismiss = { quickGenerate = false },
            onOpenParticipants = { quickGenerate = false; onOpenParticipants() },
        ) { request ->
            quickGenerate = false
            vm.quickGenerate(request, onOpenNewSong)
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}
