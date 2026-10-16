package app.sunosongwriter.ui.results

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.data.repo.ResultRepository
import app.sunosongwriter.domain.results.BuiltInIssues
import app.sunosongwriter.domain.results.IssueFix
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.Tag
import app.sunosongwriter.ui.components.TagRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class IssueFixesViewModel(private val results: ResultRepository) : ViewModel() {
    val catalog = results.catalog.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(tag: String, text: String) = viewModelScope.launch { results.saveFix(tag, text) }
    fun reset(tag: String) = viewModelScope.launch { results.resetFix(tag) }
    fun delete(tag: String) = viewModelScope.launch { results.deleteCustomTag(tag) }
    fun add(name: String, fix: String, done: (Boolean) -> Unit) = viewModelScope.launch { done(results.addCustomTag(name, fix) != null) }
}

/** The tag → fix table (spec 5.13): the guide's fixes, editable, plus the user's own tags. */
@Composable
fun IssueFixesScreen(onBack: () -> Unit) {
    val vm = appViewModel { c, _ -> IssueFixesViewModel(c.results) }
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<IssueFix?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { BackTopBar("Issue fixes", onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                modifier = Modifier.semantics { contentDescription = "Add a custom tag" },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Custom tag") },
            )
        },
    ) { padding ->
        val list = catalog ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "What Fix from results tells the model for each issue you tag on a take. The built-ins come from the Suno v6 guide's troubleshooting list; change them as you learn what works.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(list, key = { it.tag }) { fix ->
                ListItem(
                    headlineContent = { Text(fix.label) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(fix.fixText.ifBlank { "No fix written yet." }, style = MaterialTheme.typography.bodySmall)
                            when {
                                !fix.isBuiltIn -> TagRow { Tag("Custom") }
                                fix.isEdited -> TagRow { Tag("Edited") }
                            }
                        }
                    },
                    modifier = Modifier.clickable { editing = fix },
                )
                HorizontalDivider()
            }
            item { Text("", modifier = Modifier.padding(40.dp)) }
        }
    }

    editing?.let { fix ->
        FixDialog(
            title = fix.label,
            initialName = null,
            initialFix = fix.fixText,
            guideFix = BuiltInIssues.find(fix.tag)?.fixText?.takeIf { fix.isEdited },
            onDismiss = { editing = null },
            extraAction = when {
                fix.isEdited -> "Reset to the guide's fix" to { vm.reset(fix.tag); editing = null }
                !fix.isBuiltIn -> "Delete tag" to { vm.delete(fix.tag); editing = null }
                else -> null
            },
        ) { _, text, _ ->
            vm.save(fix.tag, text)
            editing = null
        }
    }
    if (adding) {
        FixDialog(title = "Custom tag", initialName = "", initialFix = "", guideFix = null, onDismiss = { adding = false }, extraAction = null) { name, text, fail ->
            vm.add(name.orEmpty(), text) { ok -> if (ok) adding = false else fail() }
        }
    }
}

@Composable
private fun FixDialog(
    title: String,
    initialName: String?,
    initialFix: String,
    guideFix: String?,
    onDismiss: () -> Unit,
    extraAction: Pair<String, () -> Unit>?,
    onSave: (name: String?, fix: String, fail: () -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var fix by remember { mutableStateOf(initialFix) }
    var nameError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                name?.let { n ->
                    OutlinedTextField(
                        value = n,
                        onValueChange = { name = it; nameError = false },
                        label = { Text("Tag") },
                        placeholder = { Text("e.g. Robotic vocals") },
                        isError = nameError,
                        supportingText = if (nameError) ({ Text("That tag already exists") }) else null,
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = fix,
                    onValueChange = { fix = it },
                    label = { Text("Fix") },
                    placeholder = { Text("What to change in the style prompt, lyrics or settings") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                guideFix?.let { Text("The guide's fix: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                extraAction?.let { (label, action) -> TextButton(onClick = action) { Text(label) } }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, fix) { nameError = true } }, enabled = name?.isNotBlank() != false) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
