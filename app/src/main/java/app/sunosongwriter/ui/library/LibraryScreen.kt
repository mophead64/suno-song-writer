package app.sunosongwriter.ui.library

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sunosongwriter.R
import app.sunosongwriter.domain.model.LibraryFilter
import app.sunosongwriter.domain.model.SongSummary
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.Tag
import app.sunosongwriter.ui.components.TagRow
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenSong: (Long) -> Unit,
    onNewSong: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm = appViewModel { c, _ -> LibraryViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::export)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::import)
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) vm.updateFilter { it.copy(query = "") }
                    }) { Icon(Icons.Filled.Search, contentDescription = "Search songs") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Folders") }, onClick = { menu = false; onOpenFolders() })
                            DropdownMenuItem(
                                text = { Text("Export library") },
                                onClick = { menu = false; exportLauncher.launch("song-writer-library-${LocalDate.now()}.json") },
                            )
                            DropdownMenuItem(
                                text = { Text("Import library") },
                                onClick = { menu = false; importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewSong,
                modifier = Modifier.semantics { contentDescription = "New song" },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New song") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val state = ui ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searching) {
                OutlinedTextField(
                    value = filter.query,
                    onValueChange = { q -> vm.updateFilter { it.copy(query = q) } },
                    placeholder = { Text("Title, artist, tag or lyric") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (filter.query.isNotEmpty()) {
                            IconButton(onClick = { vm.updateFilter { it.copy(query = "") } }) { Icon(Icons.Filled.Clear, "Clear search") }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (state.totalSongs > 0) FilterRow(state, filter, vm::updateFilter)

            when {
                state.totalSongs == 0 -> EmptyState(
                    "No songs yet",
                    "Tap New song to start one. Add backends and participants in Settings to bring in your production team.",
                )
                state.songs.isEmpty() -> EmptyState("No matching songs", "Try a different search or clear the filters.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.songs, key = { it.song.id }) { summary ->
                        SongRow(summary, onClick = { onOpenSong(summary.song.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(state: LibraryUi, filter: LibraryFilter, update: ((LibraryFilter) -> LibraryFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val folderLabel = when (filter.folderId) {
            null -> "All folders"
            LibraryFilter.UNFILED -> "Unfiled"
            else -> state.folders.firstOrNull { it.id == filter.folderId }?.name ?: "Folder"
        }
        ChipMenu(
            label = folderLabel,
            selected = filter.folderId != null,
            options = listOf<Pair<String, Long?>>("All folders" to null, "Unfiled" to LibraryFilter.UNFILED) + state.folders.map { it.name to it.id },
            onPick = { id -> update { it.copy(folderId = id) } },
        )
        if (state.artists.isNotEmpty()) {
            ChipMenu(
                label = filter.artist ?: "Artist",
                selected = filter.artist != null,
                options = listOf<Pair<String, String?>>("Any artist" to null) + state.artists.map { it to it },
                onPick = { a -> update { it.copy(artist = a) } },
            )
        }
        if (state.tags.isNotEmpty()) {
            ChipMenu(
                label = filter.tag ?: "Genre tag",
                selected = filter.tag != null,
                options = listOf<Pair<String, String?>>("Any tag" to null) + state.tags.map { it to it },
                onPick = { t -> update { it.copy(tag = t) } },
            )
        }
        if (filter.folderId != null || filter.artist != null || filter.tag != null) {
            TextButton(onClick = { update { LibraryFilter(query = it.query) } }) { Text("Clear") }
        }
    }
}

@Composable
private fun <T> ChipMenu(label: String, selected: Boolean, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}

@Composable
private fun SongRow(summary: SongSummary, onClick: () -> Unit) {
    val song = summary.song
    val edited = DateUtils.getRelativeTimeSpanString(song.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    ListItem(
        headlineContent = { Text(song.title.ifBlank { "Untitled" }) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    listOfNotNull(song.artist.takeIf { it.isNotBlank() }, summary.folderName).joinToString(" · ").ifEmpty { "No artist" },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (song.tags.isNotEmpty()) TagRow { song.tags.take(4).forEach { Tag(it) } }
                Text("Edited $edited", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = { Tag(summary.status.label) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun EmptyState(title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
