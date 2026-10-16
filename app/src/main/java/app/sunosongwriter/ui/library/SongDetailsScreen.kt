package app.sunosongwriter.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sunosongwriter.data.repo.SongRepository
import app.sunosongwriter.domain.model.Folder
import app.sunosongwriter.domain.model.Song
import app.sunosongwriter.ui.SongDetailsRoute
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Result of saving: the song's id, and whether it was just created (so the caller opens it). */
data class SavedSong(val id: Long, val created: Boolean)

class SongDetailsViewModel(savedState: SavedStateHandle, private val songs: SongRepository) : ViewModel() {
    private val route = savedState.toRoute<SongDetailsRoute>()
    val isNew = route.songId == 0L

    private val _draft = MutableStateFlow<Song?>(null)
    val draft = _draft.asStateFlow()
    private val _saved = MutableStateFlow<SavedSong?>(null)
    val saved = _saved.asStateFlow()
    private val _deleted = MutableStateFlow(false)
    val deleted = _deleted.asStateFlow()

    val folders = songs.folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val knownTags = songs.songs.map { all -> all.flatMap { it.tags }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val knownArtists = songs.songs.map { all -> all.map { it.artist }.filter { it.isNotBlank() }.distinctBy { it.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            _draft.value = if (isNew) {
                Song(0, route.folderId.takeIf { it > 0 }, "", "", "", emptyList(), 0, 0)
            } else {
                songs.observeSong(route.songId).first()
            }
        }
    }

    fun edit(change: (Song) -> Song) = _draft.update { it?.let(change) }

    fun addTag(tag: String) = edit { s ->
        val t = tag.trim()
        if (t.isEmpty() || s.tags.any { it.equals(t, ignoreCase = true) }) s else s.copy(tags = s.tags + t)
    }

    fun save() {
        val s = _draft.value ?: return
        viewModelScope.launch {
            _saved.value = if (isNew) {
                SavedSong(songs.createSong(s.title, s.artist, s.concept, s.tags, s.folderId), created = true)
            } else {
                songs.updateSong(s)
                SavedSong(s.id, created = false)
            }
        }
    }

    fun delete() = viewModelScope.launch {
        songs.deleteSong(route.songId)
        _deleted.value = true
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongDetailsScreen(onBack: () -> Unit, onSaved: (SavedSong) -> Unit, onDeleted: () -> Unit) {
    val vm = appViewModel { c, saved -> SongDetailsViewModel(saved, c.songs) }
    val draft by vm.draft.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val knownTags by vm.knownTags.collectAsStateWithLifecycle()
    val knownArtists by vm.knownArtists.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    var tagInput by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(saved) { saved?.let(onSaved) }
    LaunchedEffect(deleted) { if (deleted) onDeleted() }

    Scaffold(
        topBar = {
            BackTopBar(if (vm.isNew) "New song" else "Song details", onBack) {
                if (!vm.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete song") }
            }
        },
    ) { padding ->
        val song = draft ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = song.title,
                onValueChange = { v -> vm.edit { it.copy(title = v) } },
                label = { Text("Title") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = song.artist,
                onValueChange = { v -> vm.edit { it.copy(artist = v) } },
                label = { Text("Artist") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            val artistMatches = knownArtists.filter { it.contains(song.artist.trim(), ignoreCase = true) && !it.equals(song.artist.trim(), ignoreCase = true) }.take(5)
            if (artistMatches.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    artistMatches.forEach { a -> SuggestionChip(onClick = { vm.edit { it.copy(artist = a) } }, label = { Text(a) }) }
                }
            }

            Text("Genre tags", style = MaterialTheme.typography.titleSmall)
            if (song.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    song.tags.forEach { tag ->
                        InputChip(
                            selected = false,
                            onClick = { vm.edit { it.copy(tags = it.tags - tag) } },
                            label = { Text(tag) },
                            trailingIcon = { Icon(Icons.Filled.Close, "Remove $tag") },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = tagInput,
                onValueChange = { tagInput = it },
                label = { Text("Add a tag") },
                placeholder = { Text("e.g. pop-punk") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.addTag(tagInput); tagInput = "" }),
                trailingIcon = {
                    IconButton(onClick = { vm.addTag(tagInput); tagInput = "" }, enabled = tagInput.isNotBlank()) { Icon(Icons.Filled.Add, "Add tag") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            val tagMatches = knownTags
                .filter { known -> song.tags.none { it.equals(known, ignoreCase = true) } }
                .filter { tagInput.isBlank() || it.contains(tagInput.trim(), ignoreCase = true) }
                .take(8)
            if (tagMatches.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tagMatches.forEach { t -> SuggestionChip(onClick = { vm.addTag(t); tagInput = "" }, label = { Text(t) }) }
                }
            }

            FolderPicker(song.folderId, folders) { id -> vm.edit { it.copy(folderId = id) } }

            OutlinedTextField(
                value = song.concept,
                onValueChange = { v -> vm.edit { it.copy(concept = v) } },
                label = { Text("Concept") },
                placeholder = { Text("What it's about, mood, references, your base style description") },
                minLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = { vm.addTag(tagInput); tagInput = ""; vm.save() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.isNew) "Create song" else "Save")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this song?") },
            text = { Text("All its variants and versions are deleted. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FolderPicker(folderId: Long?, folders: List<Folder>, onPick: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("Folder: ${folders.firstOrNull { it.id == folderId }?.name ?: "Unfiled"}")
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Unfiled") }, onClick = { open = false; onPick(null) })
            folders.forEach { f -> DropdownMenuItem(text = { Text(f.name) }, onClick = { open = false; onPick(f.id) }) }
        }
    }
}
