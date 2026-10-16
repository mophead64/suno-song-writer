package app.sunosongwriter.ui.participants

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import app.sunosongwriter.AppContainer
import app.sunosongwriter.domain.model.ModelRef
import app.sunosongwriter.domain.model.Participant
import app.sunosongwriter.domain.model.Role
import app.sunosongwriter.ui.ParticipantEditRoute
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.CostLabel
import app.sunosongwriter.ui.components.ModelOption
import app.sunosongwriter.ui.components.ModelPickerDialog
import app.sunosongwriter.ui.components.buildModelOptions
import app.sunosongwriter.ui.components.describeRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelCatalog(
    val options: List<ModelOption> = emptyList(),
    val backends: List<app.sunosongwriter.domain.model.Backend> = emptyList(),
    val freeOnly: Boolean = true,
) {
    fun label(ref: ModelRef?) = describeRef(ref, options, backends)
    fun isFree(ref: ModelRef): Boolean? = options.firstOrNull { it.ref == ref }?.isFree
}

class ParticipantEditViewModel(savedState: SavedStateHandle, private val c: AppContainer) : ViewModel() {
    private val id = savedState.toRoute<ParticipantEditRoute>().participantId
    private val _draft = MutableStateFlow<Participant?>(null)
    val draft = _draft.asStateFlow()
    private val _done = MutableStateFlow(false)
    val done = _done.asStateFlow()

    val catalog = combine(c.backends.backends, c.backends.allModels, c.settings.freeOnly) { backends, models, freeOnly ->
        ModelCatalog(buildModelOptions(backends, models), backends, freeOnly)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelCatalog())

    init {
        viewModelScope.launch {
            _draft.value = (if (id != 0L) c.participants.get(id) else null)
                ?: Participant(0, name = "", avatar = "🎵", defaultRole = null, model = null, fallbacks = emptyList())
        }
    }

    fun edit(change: (Participant) -> Participant) = _draft.update { it?.let(change) }

    fun moveFallback(index: Int, delta: Int) = edit { p ->
        val list = p.fallbacks.toMutableList()
        val target = index + delta
        if (target !in list.indices) return@edit p
        list.add(target, list.removeAt(index))
        p.copy(fallbacks = list)
    }

    fun save() {
        val p = _draft.value ?: return
        viewModelScope.launch {
            c.participants.save(p.copy(name = p.name.ifBlank { "Participant" }))
            _done.value = true
        }
    }

    fun delete() {
        viewModelScope.launch {
            if (id != 0L) c.participants.delete(id)
            _done.value = true
        }
    }
}

private enum class PickerTarget { PRIMARY, FALLBACK }

@Composable
fun ParticipantEditScreen(onBack: () -> Unit) {
    val vm = appViewModel { c, saved -> ParticipantEditViewModel(saved, c) }
    val draft by vm.draft.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(done) { if (done) onBack() }

    Scaffold(
        topBar = {
            BackTopBar(if (draft?.id == 0L) "New participant" else draft?.name ?: "", onBack) {
                if (draft?.id != 0L) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete participant") }
            }
        },
    ) { padding ->
        val p = draft ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = p.avatar,
                    onValueChange = { v -> vm.edit { it.copy(avatar = v.take(4)) } },
                    label = { Text("Avatar") },
                    singleLine = true,
                    modifier = Modifier.width(96.dp),
                )
                OutlinedTextField(
                    value = p.name,
                    onValueChange = { v -> vm.edit { it.copy(name = v) } },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Gemini") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            RolePicker(p.defaultRole) { role -> vm.edit { it.copy(defaultRole = role) } }

            Text("Model", style = MaterialTheme.typography.titleMedium)
            ModelRow(p.model, catalog, onClick = { picker = PickerTarget.PRIMARY })
            val primaryFree = p.model?.let(catalog::isFree)
            if (catalog.freeOnly && primaryFree == false) {
                Warning("Free-only mode is on, so this paid model will be blocked. Pick a free model or turn off free-only mode.")
            }

            Text("Fallbacks", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tried in order when the model is rate-limited, out of daily quota or unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            p.fallbacks.forEachIndexed { index, ref ->
                ListItem(
                    headlineContent = { Text("${index + 1}. ${catalog.label(ref)}") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            catalog.isFree(ref)?.let { CostLabel(it) }
                            IconButton(onClick = { vm.moveFallback(index, -1) }, enabled = index > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                            IconButton(onClick = { vm.moveFallback(index, 1) }, enabled = index < p.fallbacks.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                            IconButton(onClick = { vm.edit { it.copy(fallbacks = it.fallbacks - ref) } }) { Icon(Icons.Filled.Close, "Remove fallback") }
                        }
                    },
                )
            }
            OutlinedButton(onClick = { picker = PickerTarget.FALLBACK }) {
                Icon(Icons.Filled.Add, null)
                Text("Add fallback", Modifier.padding(start = 8.dp))
            }

            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }

    picker?.let { target ->
        ModelPickerDialog(
            options = catalog.options,
            freeOnly = catalog.freeOnly,
            backends = catalog.backends,
            onPick = { ref ->
                when (target) {
                    PickerTarget.PRIMARY -> vm.edit { it.copy(model = ref, fallbacks = it.fallbacks - ref) }
                    PickerTarget.FALLBACK -> vm.edit { if (ref == it.model || ref in it.fallbacks) it else it.copy(fallbacks = it.fallbacks + ref) }
                }
                picker = null
            },
            onDismiss = { picker = null },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete participant?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ModelRow(ref: ModelRef?, catalog: ModelCatalog, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(catalog.label(ref), modifier = Modifier.weight(1f))
        ref?.let(catalog::isFree)?.let { CostLabel(it) }
    }
}

@Composable
private fun RolePicker(role: Role?, onPick: (Role?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("Default role: ${role?.label ?: "None"}")
            Icon(Icons.Filled.KeyboardArrowDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = { onPick(null); open = false })
            Role.entries.forEach { r -> DropdownMenuItem(text = { Text(r.label) }, onClick = { onPick(r); open = false }) }
        }
    }
}

@Composable
private fun Warning(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
