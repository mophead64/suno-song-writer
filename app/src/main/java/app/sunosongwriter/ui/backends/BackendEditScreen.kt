package app.sunosongwriter.ui.backends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sunosongwriter.data.presets.BackendPreset
import app.sunosongwriter.domain.model.FreeAccess
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.CostLabel
import app.sunosongwriter.ui.components.RelayLauncher
import app.sunosongwriter.ui.components.Tag
import app.sunosongwriter.ui.components.TagRow
import app.sunosongwriter.ui.components.rememberLocalNetworkGate

@Composable
fun BackendEditScreen(onBack: () -> Unit) {
    val vm = appViewModel { c, saved -> BackendEditViewModel(saved, c.backends, c.presets) }
    val state by vm.state.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val withLocalNetwork = rememberLocalNetworkGate(onDenied = vm::localNetworkDenied)

    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    Scaffold(
        topBar = {
            BackTopBar(if (state.isNew) "New backend" else state.draft?.name ?: "Backend", onBack) {
                if (!state.isNew) {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete backend") }
                }
            }
        },
    ) { padding ->
        val draft = state.draft
        val form = state.form
        if (draft == null || form == null) return@Scaffold
        val uri = LocalUriHandler.current

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DataUseCard(state.preset, Modifier.padding(horizontal = 16.dp)) }

            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = form.name,
                        onValueChange = { v -> vm.edit { it.copy(name = v) } },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (draft.isRelay) {
                        OutlinedTextField(
                            value = form.relayPackage,
                            onValueChange = { v -> vm.edit { it.copy(relayPackage = v.trim()) } },
                            label = { Text("App package name") },
                            supportingText = { Text("Find it in the app's Play Store link after \"id=\", e.g. com.anthropic.claude") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Relay participants don't use an API. When it's their turn, you'll get a ready-made prompt to paste into the app, and you bring the reply back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val context = LocalContext.current
                        TextButton(onClick = { RelayLauncher.copyAndOpen(context, form.relayPackage, "") }, enabled = form.relayPackage.isNotBlank()) { Text("Open the app") }
                    }
                    if (!draft.isRelay) OutlinedTextField(
                        value = form.baseUrl,
                        onValueChange = { v -> vm.edit { it.copy(baseUrl = v) } },
                        label = { Text("Base URL") },
                        supportingText = state.preset?.baseUrlHint?.let { { Text(it) } },
                        isError = form.baseUrl.isNotBlank() && !state.urlLooksValid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (draft.needsKey) KeyField(state, onChange = { v -> vm.edit { it.copy(keyInput = v) } }, onRemove = vm::removeKey)
                    state.preset?.signupUrl?.let { url ->
                        TextButton(onClick = { uri.openUri(url) }) {
                            Text(
                                when {
                                    draft.isRelay -> "Get the app on Google Play"
                                    draft.needsKey -> "Get an API key"
                                    else -> "Download / set up"
                                },
                            )
                        }
                    }
                }
            }

            if (draft.freeAccess == FreeAccess.KEY_TIER && !draft.isRelay) {
                item {
                    ListItem(
                        headlineContent = { Text("This key is on a free tier") },
                        supportingContent = {
                            Text("Its models count as free. Turn this off if the account is billed, so free-only mode blocks it.")
                        },
                        trailingContent = {
                            Switch(checked = form.isFreeTier, onCheckedChange = { v -> vm.edit { it.copy(isFreeTier = v) } })
                        },
                    )
                }
            }

            if (!draft.isRelay) item { LimitsSection(state, vm) }

            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!draft.isRelay) {
                        OutlinedButton(onClick = { withLocalNetwork(form.baseUrl, vm::testConnection) }, enabled = !state.busy && state.urlLooksValid) {
                            Text("Test connection")
                        }
                    }
                    Button(onClick = { withLocalNetwork(form.baseUrl, vm::save) }, enabled = !state.busy && state.canSave) {
                        Text(if (state.isNew) "Save" else "Save changes")
                    }
                    if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
                }
                state.status?.let { status ->
                    Text(
                        status.message,
                        color = if (status.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            if (!state.isNew && !draft.isRelay) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Models (${models.count { it.available }})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { withLocalNetwork(form.baseUrl, vm::refreshModels) }, enabled = !state.busy) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh models")
                        }
                    }
                }
                items(models, key = { it.modelId }) { model ->
                    ListItem(
                        headlineContent = { Text(model.displayName) },
                        supportingContent = {
                            val parts = listOfNotNull(
                                model.modelId.takeIf { it != model.displayName },
                                model.contextLength?.let { "${it / 1000}K context" },
                                "No longer listed".takeIf { !model.available },
                            )
                            if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
                        },
                        trailingContent = { CostLabel(draft.isFree(model)) },
                        colors = if (model.available) androidx.compose.material3.ListItemDefaults.colors()
                        else androidx.compose.material3.ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.outline),
                    )
                    HorizontalDivider()
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete backend?") },
            text = { Text("Its saved API key is deleted too. Participants using it will need a new model.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeyField(state: BackendEditState, onChange: (String) -> Unit, onRemove: () -> Unit) {
    val form = state.form ?: return
    val savedHint = state.draft?.keyHint
    val hasSavedKey = !state.isNew && savedHint != null
    OutlinedTextField(
        value = form.keyInput,
        onValueChange = onChange,
        label = { Text(if (hasSavedKey) "Replace API key" else "API key") },
        placeholder = { if (hasSavedKey) Text("Saved key ••••$savedHint") },
        supportingText = { Text("Stored encrypted on this device only. Never backed up or exported.") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (hasSavedKey) TextButton(onClick = onRemove) { Text("Remove saved key") }
}

@Composable
private fun LimitsSection(state: BackendEditState, vm: BackendEditViewModel) {
    val form = state.form ?: return
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Limits", style = MaterialTheme.typography.titleMedium)
        Text(
            state.preset?.limitsNote ?: "Leave blank for no limit. The app paces requests and counts them per day to stay under these.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Per minute", form.rpm, { v -> vm.edit { it.copy(rpm = v) } }, Modifier.weight(1f))
            NumberField("Per day", form.rpd, { v -> vm.edit { it.copy(rpd = v) } }, Modifier.weight(1f))
            NumberField("Tokens/min", form.tpm, { v -> vm.edit { it.copy(tpm = v) } }, Modifier.weight(1f))
        }
        val rules = state.draft?.modelLimits.orEmpty()
        if (rules.isNotEmpty()) {
            Text(
                "Model-specific: " + rules.joinToString { r ->
                    "\"${r.match}\" " + listOfNotNull(r.rpm?.let { "$it/min" }, r.rpd?.let { "$it/day" }).joinToString(", ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val uri = LocalUriHandler.current
        state.preset?.limitsUrl?.let { url -> TextButton(onClick = { uri.openUri(url) }) { Text("Check current limits") } }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(7)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Spec 5.2: each preset's data-use note, shown when adding a backend and on its detail screen. */
@Composable
private fun DataUseCard(preset: BackendPreset?, modifier: Modifier = Modifier) {
    val note = preset?.dataUseNote ?: return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("How your prompts are used", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                TagRow {
                    if (preset.unverified) Tag("Free tier unverified")
                    Tag("Checked ${preset.verifiedOn}")
                }
            }
        }
    }
}
