package app.sunosongwriter.ui.library

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.data.repo.SongRepository
import app.sunosongwriter.domain.model.Folder
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FoldersViewModel(private val songs: SongRepository) : ViewModel() {
    val folders = combine(songs.folders, songs.songs) { folders, all ->
        folders.map { f -> f to all.count { it.folderId == f.id } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun create(name: String) = viewModelScope.launch { songs.createFolder(name) }
    fun rename(id: Long, name: String) = viewModelScope.launch { songs.renameFolder(id, name) }
    fun delete(id: Long) = viewModelScope.launch { songs.deleteFolder(id) }
}

private sealed interface FolderDialog {
    data object Create : FolderDialog
    data class Rename(val folder: Folder) : FolderDialog
    data class Delete(val folder: Folder, val songCount: Int) : FolderDialog
}

@Composable
fun FoldersScreen(onBack: () -> Unit) {
    val vm = appViewModel { c, _ -> FoldersViewModel(c.songs) }
    val folders by vm.folders.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<FolderDialog?>(null) }

    Scaffold(
        topBar = { BackTopBar("Folders", onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialog = FolderDialog.Create },
                modifier = Modifier.semantics { contentDescription = "New folder" },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New folder") },
            )
        },
    ) { padding ->
        val list = folders ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (list.isEmpty()) item { ListItem(headlineContent = { Text("No folders yet") }, supportingContent = { Text("Folders are just a way to group songs.") }) }
            items(list, key = { it.first.id }) { (folder, count) ->
                ListItem(
                    headlineContent = { Text(folder.name) },
                    supportingContent = { Text(if (count == 1) "1 song" else "$count songs") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { dialog = FolderDialog.Rename(folder) }) { Icon(Icons.Filled.Edit, "Rename ${folder.name}") }
                            IconButton(onClick = { dialog = FolderDialog.Delete(folder, count) }) { Icon(Icons.Filled.Delete, "Delete ${folder.name}") }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        FolderDialog.Create -> NameDialog("New folder", "", "Create", onDismiss = { dialog = null }) { vm.create(it); dialog = null }
        is FolderDialog.Rename -> NameDialog("Rename folder", d.folder.name, "Rename", onDismiss = { dialog = null }) { vm.rename(d.folder.id, it); dialog = null }
        is FolderDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete \"${d.folder.name}\"?") },
            text = { Text(if (d.songCount == 0) "The folder is empty." else "Its ${d.songCount} songs won't be deleted; they'll move to Unfiled.") },
            confirmButton = { TextButton(onClick = { vm.delete(d.folder.id); dialog = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
