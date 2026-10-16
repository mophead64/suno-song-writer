package app.sunosongwriter.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.ModelInfo
import app.sunosongwriter.domain.model.ModelRef

/** A model on a backend, with its free/paid status worked out. */
data class ModelOption(val backend: Backend, val model: ModelInfo) {
    val ref = ModelRef(backend.id, model.modelId)
    val isFree = backend.isFree(model)
    val label = if (backend.isRelay) backend.name else "${model.displayName} · ${backend.name}"
}

fun buildModelOptions(backends: List<Backend>, models: List<ModelInfo>): List<ModelOption> {
    val byId = backends.associateBy { it.id }
    return models.mapNotNull { m -> byId[m.backendId]?.let { ModelOption(it, m) } }
}

/** Describes a stored [ModelRef] for display, whether or not it's still in the listings. */
fun describeRef(ref: ModelRef?, options: List<ModelOption>, backends: List<Backend>): String = when {
    ref == null -> "No model chosen"
    else -> options.firstOrNull { it.ref == ref }?.label
        ?: "${ref.modelId} · ${backends.firstOrNull { it.id == ref.backendId }?.name ?: "deleted backend"}"
}

/**
 * Picks a model across all backends. In free-only mode paid models are hidden (spec 5.2);
 * unavailable models (no longer listed by their backend) are never offered. Typing an id that isn't
 * listed offers it on each API backend, for providers whose model list is incomplete or unavailable.
 */
@Composable
fun ModelPickerDialog(
    options: List<ModelOption>,
    freeOnly: Boolean,
    onPick: (ModelRef) -> Unit,
    onDismiss: () -> Unit,
    backends: List<Backend> = emptyList(),
) {
    var query by remember { mutableStateOf("") }
    val visible = options
        .filter { it.model.available && (!freeOnly || it.isFree) }
        .filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) || it.model.modelId.contains(query.trim(), ignoreCase = true) }
        .sortedWith(compareBy({ it.backend.name.lowercase() }, { !it.isFree }, { it.model.modelId.lowercase() }))
    val typed = query.trim()
    val manual = if (typed.isEmpty() || typed.contains(' ') || options.any { it.model.modelId == typed }) {
        emptyList()
    } else {
        backends.filter { !it.isRelay && (!freeOnly || it.isFree(typed, null)) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a model") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (freeOnly) {
                    Text(
                        "Free-only mode is on, so paid models are hidden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (visible.isEmpty() && manual.isEmpty()) {
                    Text(
                        if (options.isEmpty()) "No models yet. Add a backend in Settings → Backends, then refresh its models." else "No matching models.",
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                    items(manual, key = { "manual/${it.id}" }) { backend ->
                        ListItem(
                            headlineContent = { Text("Use \u201c$typed\u201d") },
                            supportingContent = { Text("Model id on ${backend.name}, as typed") },
                            trailingContent = { CostLabel(backend.isFree(typed, null)) },
                            modifier = Modifier.clickable { onPick(ModelRef(backend.id, typed)) },
                        )
                        HorizontalDivider()
                    }
                    items(visible, key = { "${it.ref.backendId}/${it.ref.modelId}" }) { option ->
                        ListItem(
                            headlineContent = { Text(option.model.displayName) },
                            supportingContent = {
                                Text(
                                    if (option.model.displayName == option.model.modelId) option.backend.name else "${option.model.modelId} · ${option.backend.name}",
                                )
                            },
                            trailingContent = { CostLabel(option.isFree) },
                            modifier = Modifier.clickable { onPick(option.ref) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
