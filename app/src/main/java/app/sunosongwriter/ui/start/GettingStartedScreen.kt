package app.sunosongwriter.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.AppContainer
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.song.QuickStartDialog
import app.sunosongwriter.ui.song.QuickStartRequest
import app.sunosongwriter.ui.song.observeTeam
import app.sunosongwriter.ui.song.quickGenerate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which setup steps are done, worked out from what's in the app, so the checklist ticks itself off. */
data class SetupProgress(
    val hasBackend: Boolean,
    val hasTeam: Boolean,
    val hasSong: Boolean,
    val hasRunStep: Boolean,
    val hasSent: Boolean,
    val hasResults: Boolean,
) {
    val done: Int get() = listOf(hasBackend, hasTeam, hasSong, hasRunStep, hasSent, hasResults).count { it }
}

class GettingStartedViewModel(private val c: AppContainer) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    val progress = combine(
        combine(c.backends.backends, c.participants.participants, c.songs.songs) { b, p, s -> Triple(b.isNotEmpty(), p.isNotEmpty(), s.isNotEmpty()) },
        c.sessions.anyStepDone,
        c.snapshots.sentSongIds.map { it.isNotEmpty() },
        c.results.songIdsWithResults.map { it.isNotEmpty() },
    ) { (backend, team, song), step, sent, results -> SetupProgress(backend, team, song, step, sent, results) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val members = observeTeam(c).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun quickGenerate(request: QuickStartRequest, onCreated: (Long) -> Unit) = viewModelScope.launch {
        val result = quickGenerate(c, request)
        result.songId?.let(onCreated) ?: run { _message.value = result.error }
    }

    fun clearMessage() {
        _message.value = null
    }
}

/**
 * Guided setup (testing feedback): what the app does, and a checklist from connecting a model to
 * logging Suno results, with a button for each step. Opens by itself on first launch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GettingStartedScreen(
    onBack: () -> Unit,
    onAddBackend: () -> Unit,
    onStarterTeam: () -> Unit,
    onOpenParticipants: () -> Unit,
    onNewSong: () -> Unit,
    onOpenSong: (Long) -> Unit,
) {
    val vm = appViewModel { c, _ -> GettingStartedViewModel(c) }
    val progress by vm.progress.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var quickGenerate by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(topBar = { BackTopBar("Getting started", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val p = progress ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Song Writer runs a songwriting team of AI models. They write lyrics and a style prompt, review each other's work and polish it, while you have the final say. Then you copy the result into Suno yourself, and bring back how it went.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("${p.done} of 6 done", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            Step(
                1, "Connect a model", p.hasBackend,
                "A backend is where the models run: Ollama or LM Studio on your computer (free and private), a free API key from Gemini, Groq or OpenRouter, or chat apps you already use, like Claude or ChatGPT, where you copy prompts across by hand. Each backend holds its own key or address.",
            ) { Button(onClick = onAddBackend) { Text("Add a backend") } }

            Step(
                2, "Build your team", p.hasTeam,
                "Participants are models from your backends with a name and a role, like “Gemini, lyricist”. Build my team makes one from each backend you've added, with roles assigned. No keys to enter again.",
            ) {
                Button(onClick = onStarterTeam, enabled = p.hasBackend) { Text("Build my team") }
                OutlinedButton(onClick = onOpenParticipants) { Text("Participants") }
            }

            Step(
                3, "Start a song", p.hasSong,
                "Quick generate turns a one-line idea into a first draft with lyrics, a style and settings. Or create a song and write it yourself.",
            ) {
                Button(onClick = { quickGenerate = true }, enabled = p.hasTeam) { Text("Quick generate") }
                OutlinedButton(onClick = onNewSong) { Text("New song") }
            }

            Step(
                4, "Work on it with your team", p.hasRunStep,
                "On a song's Team tab, run single steps (write, review, implement the suggestions you accept), or start a Writers' Room: the whole team proposes and debates in rounds while you chair, ending with a Consensus version and a version refined with the Suno v6 guide.",
            )

            Step(
                5, "Send it to Suno", p.hasSent,
                "The send icon on a song opens the handoff: copy the style and lyrics into Suno, tick off the sliders as you set them, and mark it sent. The app keeps an exact copy of what you sent.",
            )

            Step(
                6, "Log what came back", p.hasResults,
                "Open the sent copy from the song's History tab: paste the Suno links, rate the takes and tag problems like “lyrics cut off”. Fix from results turns those into a better version, and songs that work can be saved as recipes to reuse.",
            )
        }
    }

    if (quickGenerate) {
        QuickStartDialog(
            members = members,
            initialIdea = "",
            newSong = true,
            replacesContent = false,
            onDismiss = { quickGenerate = false },
            onOpenParticipants = { quickGenerate = false; onOpenParticipants() },
        ) { request ->
            quickGenerate = false
            vm.quickGenerate(request, onOpenSong)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Step(number: Int, title: String, done: Boolean, body: String, actions: (@Composable () -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (done) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.semantics(mergeDescendants = true) {
                    heading()
                    contentDescription = "Step $number: $title. " + if (done) "Done" else "Not done yet"
                },
            ) {
                if (done) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                } else {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("$number", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodySmall)
            if (actions != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}
