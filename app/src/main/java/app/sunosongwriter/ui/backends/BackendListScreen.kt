package app.sunosongwriter.ui.backends

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.data.repo.BackendRepository
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.Tag
import app.sunosongwriter.ui.components.TagRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class BackendRow(val backend: Backend, val availableModels: Int, val freeModels: Int)

class BackendListViewModel(backends: BackendRepository) : ViewModel() {
    val rows = combine(backends.backends, backends.allModels) { list, models ->
        list.map { b ->
            val mine = models.filter { it.backendId == b.id && it.available }
            BackendRow(b, mine.size, mine.count { b.isFree(it) })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun BackendListScreen(onBack: () -> Unit, onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    val vm = appViewModel { c, _ -> BackendListViewModel(c.backends) }
    val rows by vm.rows.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { BackTopBar("Backends", onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                // This Material 3 version doesn't expose the FAB's text to accessibility services.
                modifier = Modifier.semantics { contentDescription = "Add backend" },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add backend") },
            )
        },
    ) { padding ->
        val list = rows ?: return@Scaffold
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No backends yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add Ollama on your computer, or a free API tier like Gemini or OpenRouter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(list, key = { it.backend.id }) { row ->
                ListItem(
                    headlineContent = { Text(row.backend.name) },
                    supportingContent = {
                        Column {
                            Text(row.backend.baseUrl, style = MaterialTheme.typography.bodySmall)
                            Text(
                                when {
                                    row.availableModels == 0 -> "No models listed. Open to test the connection."
                                    row.freeModels == row.availableModels -> "${row.availableModels} models"
                                    else -> "${row.availableModels} models, ${row.freeModels} free"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    trailingContent = { TagRow { Tag(row.backend.costLabel) } },
                    modifier = Modifier.clickable { onOpen(row.backend.id) },
                )
                HorizontalDivider()
            }
        }
    }
}
