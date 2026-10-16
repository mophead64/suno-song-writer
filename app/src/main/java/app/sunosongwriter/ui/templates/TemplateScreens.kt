package app.sunosongwriter.ui.templates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sunosongwriter.AppContainer
import app.sunosongwriter.data.repo.TemplateDefault
import app.sunosongwriter.domain.model.Participant
import app.sunosongwriter.domain.model.Song
import app.sunosongwriter.domain.prompt.PromptTemplate
import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.domain.prompt.TemplateContext
import app.sunosongwriter.domain.prompt.TemplateKey
import app.sunosongwriter.domain.prompt.TemplateRenderer
import app.sunosongwriter.domain.workflow.SessionRunner
import app.sunosongwriter.ui.TemplateEditRoute
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.Tag
import app.sunosongwriter.ui.components.TagRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// List

class TemplateListViewModel(c: AppContainer) : ViewModel() {
    val rows = combine(c.templates.templates, c.templates.defaults) { templates, defaults ->
        templates.map { t -> t to defaults.filter { it.key == t.key } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun TemplateListScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val vm = appViewModel { c, _ -> TemplateListViewModel(c) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("Prompt templates", onBack) }) { padding ->
        val list = rows ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "What each step asks the model. Built-ins are read-only: make a copy to change one, then set your copy as the default.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            SessionAction.entries.forEach { action ->
                val forAction = list.filter { it.first.action == action }
                item(key = "h-${action.name}") {
                    Text(action.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
                }
                items(forAction, key = { it.first.key.value }) { (template, defaults) ->
                    val usedByDefault = defaults.any { it.isGlobal } || (template.isBuiltIn && list.none { (t, d) -> t.action == action && d.any { it.isGlobal } })
                    ListItem(
                        leadingContent = { if (template.isBuiltIn) Icon(Icons.Filled.Lock, contentDescription = "Built-in") },
                        headlineContent = { Text(template.name) },
                        supportingContent = {
                            TagRow {
                                if (usedByDefault) Tag("Default")
                                val forSome = defaults.count { !it.isGlobal }
                                if (forSome > 0) Tag("Default for $forSome participant${if (forSome > 1) "s" else ""}")
                            }
                        },
                        modifier = Modifier.clickable { onOpen(template.key.value) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// Editor

data class TemplateEditUi(
    val template: PromptTemplate,
    val defaults: List<TemplateDefault>,
    val participants: List<Participant>,
    val songs: List<Song>,
)

class TemplateEditViewModel(savedState: SavedStateHandle, private val c: AppContainer) : ViewModel() {
    private val key = MutableStateFlow(TemplateKey(savedState.toRoute<TemplateEditRoute>().key))
    private val _draft = MutableStateFlow<PromptTemplate?>(null)
    val draft = _draft.asStateFlow()
    private val _closed = MutableStateFlow(false)
    val closed = _closed.asStateFlow()

    val ui = combine(key, c.templates.templates, c.templates.defaults, c.participants.participants, c.songs.songs) { k, templates, defaults, participants, songs ->
        templates.firstOrNull { it.key == k }?.let { TemplateEditUi(it, defaults.filter { d -> d.action == it.action }, participants, songs) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { _draft.value = c.templates.get(key.value) }
    }

    fun edit(change: (PromptTemplate) -> PromptTemplate) {
        _draft.value = _draft.value?.let(change)
    }

    fun makeCopy() = viewModelScope.launch {
        c.templates.copy(key.value)?.let { newKey ->
            key.value = newKey
            _draft.value = c.templates.get(newKey)
        }
    }

    fun save() = viewModelScope.launch {
        _draft.value?.let { c.templates.save(it) }
        _closed.value = true
    }

    fun delete() = viewModelScope.launch {
        c.templates.delete(key.value)
        _closed.value = true
    }

    /** Toggles this template as the default for everyone (participant 0) or one participant. */
    fun toggleDefault(participantId: Long, on: Boolean) = viewModelScope.launch {
        val t = _draft.value ?: return@launch
        if (on) c.templates.setDefault(t.action, participantId, t.key) else c.templates.clearDefault(t.action, participantId)
    }

    suspend fun preview(song: Song?): String {
        val t = _draft.value ?: return ""
        val variant = song?.let { c.songs.observeVariants(it.id).first().firstOrNull() }
        val content = variant?.let { c.songs.latest(it.id)?.content }
        return TemplateRenderer.render(
            t.body,
            TemplateContext(
                title = song?.title.orEmpty(),
                artist = song?.artist.orEmpty(),
                tags = song?.tags?.joinToString(", ").orEmpty(),
                concept = song?.concept.orEmpty(),
                style = content?.style?.flatText().orEmpty(),
                lyrics = content?.lyrics.orEmpty(),
                sliders = content?.settings?.let(SessionRunner::describeSettings).orEmpty(),
                suggestions = "1. [Lyrics · Chorus] (accepted suggestions appear here)",
                results = "(logged Suno results appear here, from iteration 6)",
                guide = "(the ${c.guide.length}-character Suno v6 guide is inserted here)",
                instructions = "(what you type when running the step)",
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateEditScreen(onBack: () -> Unit) {
    val vm = appViewModel { c, saved -> TemplateEditViewModel(saved, c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val closed by vm.closed.collectAsStateWithLifecycle()
    var body by remember(draft?.key) { mutableStateOf(TextFieldValue(draft?.body.orEmpty())) }
    var previewSong by remember { mutableStateOf<Song?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(closed) { if (closed) onBack() }

    Scaffold(
        topBar = {
            BackTopBar(if (draft?.isBuiltIn == true) "Built-in template" else "Edit template", onBack) {
                if (draft?.isBuiltIn == false) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete template") }
            }
        },
    ) { padding ->
        val t = draft ?: return@Scaffold
        val state = ui ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (t.isBuiltIn) {
                Text(t.name, style = MaterialTheme.typography.titleLarge)
                Text("${t.action.label} · read-only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = vm::makeCopy) { Text("Make an editable copy") }
                Card { SelectionContainer { Text(t.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)) } }
            } else {
                OutlinedTextField(value = t.name, onValueChange = { v -> vm.edit { it.copy(name = v) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ActionPicker(t.action) { a -> vm.edit { it.copy(action = a) } }
                Text("Insert a variable", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TemplateContext.VARIABLES.forEach { (name, desc) ->
                        AssistChip(
                            onClick = {
                                val insert = "{{$name}}"
                                val text = body.text.replaceRange(body.selection.start, body.selection.end, insert)
                                body = TextFieldValue(text, TextRange(body.selection.start + insert.length))
                                vm.edit { it.copy(body = text) }
                            },
                            label = { Text(name) },
                            modifier = Modifier.semantics { contentDescription = "Insert $name: $desc" },
                        )
                    }
                }
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it; vm.edit { d -> d.copy(body = it.text) } },
                    label = { Text("Template") },
                    minLines = 12,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                val unknown = TemplateRenderer.unknownVariables(body.text)
                if (unknown.isNotEmpty()) Text("Unknown variables (left as typed): ${unknown.joinToString { "{{$it}}" }}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            }

            Text("Use by default", style = MaterialTheme.typography.titleMedium)
            DefaultRow("For everyone", state.defaults.any { it.isGlobal && it.key == t.key }) { vm.toggleDefault(TemplateDefault.GLOBAL, it) }
            state.participants.forEach { p ->
                DefaultRow("For ${p.avatar} ${p.name}", state.defaults.any { it.participantId == p.id && it.key == t.key }) { vm.toggleDefault(p.id, it) }
            }

            Text("Preview", style = MaterialTheme.typography.titleMedium)
            SongPicker(previewSong, state.songs) { previewSong = it }
            LaunchedEffect(previewSong, body.text, t.key) { preview = vm.preview(previewSong) }
            preview?.let {
                Card { Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)) } } }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete template?") },
            text = { Text("Steps that used it as a default go back to the built-in.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DefaultRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun ActionPicker(action: SessionAction, onPick: (SessionAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("Action: ${action.label}")
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SessionAction.entries.forEach { a -> DropdownMenuItem(text = { Text(a.label) }, onClick = { open = false; onPick(a) }) }
        }
    }
}

@Composable
private fun SongPicker(song: Song?, songs: List<Song>, onPick: (Song?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("Song: ${song?.title?.ifBlank { "Untitled" } ?: "None (placeholders)"}")
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("None (placeholders)") }, onClick = { open = false; onPick(null) })
            songs.forEach { s -> DropdownMenuItem(text = { Text(s.title.ifBlank { "Untitled" }) }, onClick = { open = false; onPick(s) }) }
        }
    }
}
