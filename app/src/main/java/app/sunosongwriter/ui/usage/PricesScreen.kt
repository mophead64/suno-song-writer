package app.sunosongwriter.ui.usage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.AppContainer
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.ModelInfo
import app.sunosongwriter.domain.usage.Price
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.song.Picker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PricesUi(val prices: List<Price>, val backends: List<Backend>, val models: List<ModelInfo>)

class PricesViewModel(private val c: AppContainer) : ViewModel() {
    val ui = combine(c.usage.prices, c.backends.backends, c.backends.allModels) { prices, backends, models ->
        PricesUi(prices, backends.filter { !it.isRelay }, models)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(price: Price) = viewModelScope.launch { c.usage.savePrice(price) }
    fun delete(price: Price) = viewModelScope.launch { c.usage.deletePrice(price.backendId, price.modelId) }
}

/** The editable price table (spec 5.17): estimates costs for paid models whose provider doesn't report them. */
@Composable
fun PricesScreen(onBack: () -> Unit) {
    val vm = appViewModel { c, _ -> PricesViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Price?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { BackTopBar("Price table", onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                modifier = Modifier.semantics { contentDescription = "Add a price" },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add a price") },
            )
        },
    ) { padding ->
        val state = ui ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Prices in US dollars per million tokens, from the provider's pricing page. Used to estimate costs for paid models whose provider doesn't report them (OpenRouter does). Free models always cost \$0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (state.prices.isEmpty()) item { Text("No prices yet.", modifier = Modifier.padding(horizontal = 16.dp)) }
            items(state.prices, key = { "${it.backendId}/${it.modelId}" }) { p ->
                ListItem(
                    headlineContent = { Text(p.modelId) },
                    supportingContent = {
                        Text("${state.backends.firstOrNull { it.id == p.backendId }?.name ?: "Deleted backend"} · in \$${p.inputPerMTok} · out \$${p.outputPerMTok} per million tokens")
                    },
                    modifier = Modifier.clickable { editing = p },
                )
                HorizontalDivider()
            }
        }
    }

    val state = ui
    if (state != null && (adding || editing != null)) {
        PriceDialog(
            existing = editing,
            backends = state.backends,
            models = state.models,
            onDismiss = { adding = false; editing = null },
            onDelete = editing?.let { p -> { vm.delete(p); editing = null } },
        ) { price ->
            vm.save(price)
            adding = false
            editing = null
        }
    }
}

@Composable
private fun PriceDialog(
    existing: Price?,
    backends: List<Backend>,
    models: List<ModelInfo>,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (Price) -> Unit,
) {
    var backendId by remember { mutableStateOf(existing?.backendId ?: backends.firstOrNull()?.id) }
    var modelId by remember { mutableStateOf(existing?.modelId.orEmpty()) }
    var input by remember { mutableStateOf(existing?.inputPerMTok?.toString().orEmpty()) }
    var output by remember { mutableStateOf(existing?.outputPerMTok?.toString().orEmpty()) }
    val inPrice = input.trim().toDoubleOrNull()?.takeIf { it >= 0 }
    val outPrice = output.trim().toDoubleOrNull()?.takeIf { it >= 0 }
    val backendModels = models.filter { it.backendId == backendId && it.available }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add a price" else "Edit price") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing == null) {
                    Picker("Backend", backends.firstOrNull { it.id == backendId }?.name ?: "Choose", backends.map { it.name to it.id }) { backendId = it; modelId = "" }
                    if (backendModels.isNotEmpty()) {
                        Picker("Model", modelId.ifBlank { "Choose, or type below" }, backendModels.map { it.displayName to it.modelId }) { modelId = it }
                    }
                    OutlinedTextField(modelId, { modelId = it }, label = { Text("Model id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(existing.modelId, style = MaterialTheme.typography.titleSmall)
                }
                val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                OutlinedTextField(input, { input = it }, label = { Text("Input, \$ per million tokens") }, singleLine = true, keyboardOptions = decimal)
                OutlinedTextField(output, { output = it }, label = { Text("Output, \$ per million tokens") }, singleLine = true, keyboardOptions = decimal)
                onDelete?.let { TextButton(onClick = it) { Text("Delete price") } }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(Price(backendId!!, modelId.trim(), inPrice!!, outPrice!!)) },
                enabled = backendId != null && modelId.isNotBlank() && inPrice != null && outPrice != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
