package app.sunosongwriter.ui.results

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.DateUtils
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sunosongwriter.AppContainer
import app.sunosongwriter.domain.model.Snapshot
import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.domain.results.IssueCatalog
import app.sunosongwriter.domain.results.IssueFix
import app.sunosongwriter.domain.results.Recipe
import app.sunosongwriter.domain.results.ResultsBrief
import app.sunosongwriter.domain.results.SongWinner
import app.sunosongwriter.domain.results.SunoLinks
import app.sunosongwriter.domain.results.Take
import app.sunosongwriter.domain.results.variantName
import app.sunosongwriter.domain.workflow.RunException
import app.sunosongwriter.domain.workflow.RunRequest
import app.sunosongwriter.ui.ResultsRoute
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.Tag
import app.sunosongwriter.ui.song.ParticipantField
import app.sunosongwriter.ui.song.TeamMember
import app.sunosongwriter.ui.song.observeTeam
import app.sunosongwriter.ui.song.suggestedMember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ResultsUi(
    val songTitle: String,
    val snapshot: Snapshot,
    val takes: List<Take>,
    val catalog: List<IssueFix>,
    val winner: SongWinner?,
    val recipe: Recipe?,
    val members: List<TeamMember>,
) {
    /** Issues raised across the takes, with their fixes, as Fix from results will send them. */
    val targeted: List<IssueFix> get() = ResultsBrief.issueTags(takes, catalog).map { tag -> catalog.firstOrNull { it.tag == tag } ?: IssueFix(tag, tag, "", false) }
    val isWinner: Boolean get() = winner?.variantName == snapshot.variantName
}

/** A message, optionally offering to open the song (after starting a fix). */
data class ResultsNotice(val text: String, val openSong: Boolean = false)

/** The results log for one snapshot (spec 5.12), Fix from results (5.13) and saving it as a recipe (5.14). */
class ResultsViewModel(savedState: SavedStateHandle, private val c: AppContainer) : ViewModel() {
    val route = savedState.toRoute<ResultsRoute>()
    private val _notice = MutableStateFlow<ResultsNotice?>(null)
    val notice = _notice.asStateFlow()

    val ui = combine(
        c.songs.observeSong(route.songId),
        c.snapshots.observeForSong(route.songId),
        c.results.observeTakes(route.snapshotId),
        c.results.catalog,
        c.results.observeWinner(route.songId),
    ) { song, snapshots, takes, catalog, winner ->
        val snapshot = snapshots.firstOrNull { it.id == route.snapshotId } ?: return@combine null
        ResultsUi(song?.title?.ifBlank { "Untitled" }.orEmpty(), snapshot, takes, catalog, winner, recipe = null, members = emptyList())
    }.combine(c.recipes.observeForSnapshot(route.snapshotId)) { ui, recipe -> ui?.copy(recipe = recipe) }
        .combine(observeTeam(c)) { ui, members -> ui?.copy(members = members) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clearNotice() {
        _notice.value = null
    }

    fun saveTake(take: Take?, link: String?, rating: Int?, notes: String, issues: List<String>) = viewModelScope.launch {
        if (take == null) c.results.addTake(route.snapshotId, link, rating, notes, issues) else c.results.updateTake(take.id, link, rating, notes, issues)
    }

    fun deleteTake(take: Take) = viewModelScope.launch { c.results.deleteTake(take.id) }

    fun addCustomTag(name: String, done: (String?) -> Unit) = viewModelScope.launch { done(c.results.addCustomTag(name)) }

    fun markWinner() {
        val snapshot = ui.value?.snapshot ?: return
        viewModelScope.launch {
            c.results.markWinner(snapshot)
            _notice.value = ResultsNotice("${snapshot.variantName} marked as this song's winner.")
        }
    }

    fun clearWinner() = viewModelScope.launch { c.results.clearWinner(route.songId) }

    suspend fun nextRecipeName(): String = c.recipes.nextName()

    fun saveRecipe(name: String) {
        val state = ui.value ?: return
        viewModelScope.launch {
            val song = c.songs.observeSong(route.songId).first() ?: return@launch
            c.recipes.saveFromSnapshot(state.snapshot, song, name, state.takes.mapNotNull { it.rating }.maxOrNull())
            _notice.value = ResultsNotice("Saved as recipe “${name.trim()}”.")
        }
    }

    /** Fix from results on the variant the snapshot was sent from; the new version shows on the song's Team tab. */
    fun runFix(participantId: Long, instructions: String) {
        val state = ui.value ?: return
        viewModelScope.launch {
            val variantId = c.songs.version(state.snapshot.versionId)?.variantId
            if (variantId == null) {
                _notice.value = ResultsNotice("${state.snapshot.variantName} was deleted, so there's nowhere to save the fix.")
                return@launch
            }
            _notice.value = try {
                c.runner.start(RunRequest(route.songId, variantId, SessionAction.FIX, participantId, instructions = instructions, snapshotId = route.snapshotId))
                ResultsNotice("Fixing ${state.snapshot.variantName}. The new version appears on the song's Team tab.", openSong = true)
            } catch (e: RunException) {
                ResultsNotice(e.message ?: "Couldn't start the fix.")
            }
        }
    }
}

private sealed interface ResultsDialog {
    data class EditTake(val take: Take?) : ResultsDialog
    data class DeleteTake(val take: Take) : ResultsDialog
    data class SaveRecipe(val suggested: String) : ResultsDialog
}

@Composable
fun ResultsScreen(onBack: () -> Unit, onOpenSnapshot: (songId: Long, snapshotId: Long) -> Unit, onOpenSong: (Long) -> Unit, onOpenRecipe: (Long) -> Unit) {
    val vm = appViewModel { c, saved -> ResultsViewModel(saved, c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<ResultsDialog?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(notice) {
        notice?.let { n ->
            // Cleared after showing: clearing first would restart this effect and cancel the snackbar.
            val result = snackbar.showSnackbar(n.text, actionLabel = if (n.openSong) "Open song" else null, withDismissAction = !n.openSong)
            vm.clearNotice()
            if (result == SnackbarResult.ActionPerformed) onOpenSong(vm.route.songId)
        }
    }

    Scaffold(topBar = { BackTopBar("Results", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val state = ui ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${state.songTitle} · ${state.snapshot.versionLabel}", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Sent ${DateUtils.getRelativeTimeSpanString(state.snapshot.sentAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onOpenSnapshot(vm.route.songId, vm.route.snapshotId) }) { Text("What was sent") }
            }

            WinnerCard(state, onMark = vm::markWinner, onClear = vm::clearWinner)

            Text("Takes", style = MaterialTheme.typography.titleMedium)
            if (state.takes.isEmpty()) {
                Text(
                    "Paste the Suno link for each take (usually two per generation), rate it, and tag what went wrong.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.takes.forEach { take ->
                TakeCard(take, state.catalog, onEdit = { dialog = ResultsDialog.EditTake(take) }, onDelete = { dialog = ResultsDialog.DeleteTake(take) })
            }
            FilledTonalButton(onClick = { dialog = ResultsDialog.EditTake(null) }, modifier = Modifier.fillMaxWidth()) { Text("Log a take") }

            if (state.takes.isNotEmpty()) FixCard(state, onRun = vm::runFix)

            RecipeCard(state.recipe, onSave = { scope.launch { dialog = ResultsDialog.SaveRecipe(vm.nextRecipeName()) } }, onOpen = onOpenRecipe)
        }
    }

    val state = ui
    when (val d = dialog) {
        null -> Unit
        is ResultsDialog.EditTake -> TakeDialog(
            take = d.take,
            catalog = state?.catalog.orEmpty(),
            onDismiss = { dialog = null },
            onAddCustomTag = vm::addCustomTag,
        ) { link, rating, notes, issues ->
            vm.saveTake(d.take, link, rating, notes, issues)
            dialog = null
        }
        is ResultsDialog.DeleteTake -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete take ${d.take.index}?") },
            text = { Text("Its link, rating, tags and notes are removed.") },
            confirmButton = { TextButton(onClick = { vm.deleteTake(d.take); dialog = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is ResultsDialog.SaveRecipe -> RecipeNameDialog(d.suggested, onDismiss = { dialog = null }) { name -> vm.saveRecipe(name); dialog = null }
    }
}

@Composable
private fun WinnerCard(state: ResultsUi, onMark: () -> Unit, onClear: () -> Unit) {
    val variant = state.snapshot.variantName
    Card(colors = CardDefaults.cardColors(containerColor = if (state.isWinner) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                state.isWinner -> {
                    Text("★ $variant won for this song", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = onClear) { Text("Undo") }
                }
                else -> {
                    Text(
                        state.winner?.let { "${it.variantName} is marked as the winner." } ?: "Which variant won? Mark it once you've heard them all.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onMark) { Text("$variant won") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TakeCard(take: Take, catalog: List<IssueFix>, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Take ${take.index}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    stars(take.rating),
                    color = if (take.rating == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { contentDescription = take.rating?.let { "Rated $it of 5" } ?: "Not rated" },
                )
            }
            take.link?.let { link ->
                if (SunoLinks.isWebLink(link)) {
                    TextButton(onClick = { openLink(context, link) }) { Text(link, maxLines = 1) }
                } else {
                    Text(link, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (take.issues.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    take.issues.forEach { Tag(IssueCatalog.label(it, catalog), container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer) }
                }
            }
            if (take.notes.isNotBlank()) Text(take.notes, style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/** Fix from results (spec 5.13): shows what will be targeted, then runs the Fix template with a participant. */
@Composable
private fun FixCard(state: ResultsUi, onRun: (Long, String) -> Unit) {
    var picked by remember { mutableStateOf<Long?>(null) }
    var instructions by remember { mutableStateOf("") }
    val memberId = picked ?: suggestedMember(state.members, SessionAction.FIX)
    val member = state.members.firstOrNull { it.participant.id == memberId }
    val targeted = state.targeted
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fix from results", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sends what was sent, these takes and the matching fixes to a model. The result is the next version of ${state.snapshot.variantName}.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (targeted.isEmpty()) {
                Text("No issues tagged, so only your notes guide the fix.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            targeted.forEach { fix ->
                Column {
                    Text(fix.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(fix.fixText.ifBlank { "No fix written for this tag yet (Settings → Issue fixes)." }, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.members.isEmpty()) {
                Text("Add participants in Settings → Participants to run a fix.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            ParticipantField(state.members, memberId) { picked = it }
            OutlinedTextField(
                value = instructions,
                onValueChange = { instructions = it },
                label = { Text("Extra instructions (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { memberId?.let { onRun(it, instructions) } },
                enabled = member != null && member.blockedReason == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Fix ${state.snapshot.variantName}") }
        }
    }
}

@Composable
private fun RecipeCard(recipe: Recipe?, onSave: () -> Unit, onOpen: (Long) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recipe", style = MaterialTheme.typography.titleMedium)
            if (recipe == null) {
                Text("Worked well? Save it exactly as it was sent, to reuse its style and settings in new songs.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onSave) { Text("Save as recipe") }
            } else {
                Text("Saved as “${recipe.name}”. Recipes never change.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onOpen(recipe.id) }) { Text("Open recipe") }
            }
        }
    }
}

@Composable
internal fun RecipeNameDialog(suggested: String, title: String = "Save as recipe", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(suggested) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun openLink(context: android.content.Context, link: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "No app can open that link.", android.widget.Toast.LENGTH_SHORT).show()
    }
}
