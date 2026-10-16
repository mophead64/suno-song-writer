package app.sunosongwriter.ui.song

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sunosongwriter.data.repo.SongRepository
import app.sunosongwriter.domain.diff.ContentDiff
import app.sunosongwriter.domain.diff.SideBySide
import app.sunosongwriter.domain.diff.SideBySideContent
import app.sunosongwriter.domain.diff.TextDiff
import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.Version
import app.sunosongwriter.ui.CompareRoute
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.ContentDiffView
import app.sunosongwriter.ui.components.SideBySideView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VersionChoice(val version: Version, val label: String, val isLatestInVariant: Boolean)

data class CompareUi(
    val choices: List<VersionChoice>,
    /** Null means "empty", e.g. comparing a variant's first version with nothing. */
    val from: VersionChoice?,
    val to: VersionChoice,
    val diff: ContentDiff,
    val sideBySide: SideBySideContent,
)

class CompareViewModel(savedState: SavedStateHandle, private val songs: SongRepository) : ViewModel() {
    private val route = savedState.toRoute<CompareRoute>()
    private val selection = MutableStateFlow(route.fromId to route.toId)
    val sideBySide = MutableStateFlow(route.sideBySide)
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    val ui = combine(songs.observeVariants(route.songId), songs.observeVersions(route.songId), selection) { variants, versions, (fromId, toId) ->
        val names = variants.associate { it.id to it.name }
        val latest = versions.groupBy { it.variantId }.mapValues { (_, vs) -> vs.maxOf { it.number } }
        val choices = versions
            .sortedWith(compareBy({ variants.indexOfFirst { v -> v.id == it.variantId } }, { -it.number }))
            .map { v -> VersionChoice(v, "${names[v.variantId] ?: "?"} v${v.number}", latest[v.variantId] == v.number) }
        val to = choices.firstOrNull { it.version.id == toId } ?: choices.firstOrNull() ?: return@combine null
        val from = choices.firstOrNull { it.version.id == fromId }
        val old = from?.version?.content ?: SongContent()
        CompareUi(choices, from, to, TextDiff.content(old, to.version.content), SideBySide.content(old, to.version.content))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFrom(id: Long) = selection.value.let { selection.value = id to it.second }
    fun setTo(id: Long) = selection.value.let { selection.value = it.first to id }
    fun swap() = selection.value.let { (a, b) -> if (a != 0L) selection.value = b to a }

    fun restore(choice: VersionChoice) = viewModelScope.launch {
        val restored = songs.restore(choice.version.id)
        _message.value = restored?.let { "Restored as ${choice.label.substringBeforeLast(" v")} v${it.number}" } ?: "Already the current content."
    }

    fun clearMessage() {
        _message.value = null
    }
}

@Composable
fun CompareScreen(onBack: () -> Unit) {
    val vm = appViewModel { c, saved -> CompareViewModel(saved, c.songs) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val sideBySide by vm.sideBySide.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(topBar = { BackTopBar(if (sideBySide) "Side by side" else "Compare versions", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val state = ui ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VersionPicker("From", state.from, state.choices, allowEmpty = true, onPick = vm::setFrom)
                    VersionPicker("To", state.to, state.choices, allowEmpty = false, onPick = vm::setTo)
                }
                TextButton(onClick = vm::swap, enabled = state.from != null) { Text("Swap") }
            }
            listOfNotNull(state.from, state.to).filter { !it.isLatestInVariant }.distinctBy { it.version.id }.forEach { choice ->
                OutlinedButton(onClick = { vm.restore(choice) }) { Text("Restore ${choice.label}") }
            }
            state.to.version.changeNote.takeIf { it.isNotBlank() }?.let {
                Text("${state.to.label}: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !sideBySide, onClick = { vm.sideBySide.value = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Changes") }
                SegmentedButton(selected = sideBySide, onClick = { vm.sideBySide.value = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Side by side") }
            }
            HorizontalDivider()
            if (sideBySide) {
                SideBySideView(state.sideBySide, state.from?.label ?: "Empty", state.to.label, Modifier.fillMaxWidth())
            } else {
                ContentDiffView(state.diff, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun VersionPicker(label: String, selected: VersionChoice?, choices: List<VersionChoice>, allowEmpty: Boolean, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${selected?.label ?: "Empty"}", modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowEmpty) DropdownMenuItem(text = { Text("Empty") }, onClick = { open = false; onPick(0) })
            choices.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.label + (c.version.changeNote.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")) },
                    onClick = { open = false; onPick(c.version.id) },
                )
            }
        }
    }
}
