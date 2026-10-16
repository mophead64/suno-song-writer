package app.sunosongwriter.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sunosongwriter.domain.lint.SunoLimits
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.data.settings.SettingsRepository
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.Tag
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {
    val freeOnly = settings.freeOnly.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val limits = settings.sunoLimits.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SunoLimits())

    fun setLimits(limits: SunoLimits) {
        viewModelScope.launch { settings.setSunoLimits(limits) }
    }

    fun setFreeOnly(enabled: Boolean) {
        viewModelScope.launch { settings.setFreeOnly(enabled) }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenBackends: () -> Unit,
    onOpenParticipants: () -> Unit,
    onOpenPlayground: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenIssueFixes: () -> Unit,
    onOpenUsage: () -> Unit,
) {
    val vm = appViewModel { c, _ -> SettingsViewModel(c.settings) }
    val freeOnly by vm.freeOnly.collectAsStateWithLifecycle()
    val limits by vm.limits.collectAsStateWithLifecycle()
    var editLimits by remember { mutableStateOf(false) }

    Scaffold(topBar = { BackTopBar("Settings", onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                headlineContent = { Text("Free-only mode") },
                supportingContent = {
                    Text("Only free models can be picked or called. Paid models are blocked even if a paid key is set up.")
                },
                trailingContent = { Switch(checked = freeOnly, onCheckedChange = null) },
                modifier = Modifier
                    .clickable { vm.setFreeOnly(!freeOnly) }
                    .semantics { role = Role.Switch },
            )
            HorizontalDivider()
            NavItem("Backends", "Models on your computer, API providers and chat apps", onOpenBackends)
            NavItem("Participants", "Your writers' room: models with a name, role and fallbacks", onOpenParticipants)
            NavItem("Usage and limits", "Tokens and cost by day, month, backend and song; spend limits; OpenRouter credit", onOpenUsage)
            ListItem(
                headlineContent = { Text("Suno limits") },
                supportingContent = { Text("Style ${"%,d".format(limits.styleChars)} · lyrics ${"%,d".format(limits.lyricsChars)} characters · tags up to ${limits.tagWords} words") },
                modifier = Modifier.clickable { editLimits = true },
            )
            NavItem("Prompt templates", "What each step asks the model; copy and edit the built-ins", onOpenTemplates)
            NavItem("Issue fixes", "What Fix from results changes for each problem you tag on a take", onOpenIssueFixes)
            NavItem("Playground", "Send a test prompt to a participant", onOpenPlayground)
        }
    }

    if (editLimits) LimitsDialog(limits, onDismiss = { editLimits = false }) { vm.setLimits(it); editLimits = false }
}

/** Suno changes its limits, so they're editable (spec 5.9). Defaults checked 2026-10-16 for v6. */
@Composable
private fun LimitsDialog(current: SunoLimits, onDismiss: () -> Unit, onSave: (SunoLimits) -> Unit) {
    var style by remember { mutableStateOf(current.styleChars.toString()) }
    var lyrics by remember { mutableStateOf(current.lyricsChars.toString()) }
    var words by remember { mutableStateOf(current.tagWords.toString()) }
    val parsed = listOf(style, lyrics, words).map { it.toIntOrNull()?.takeIf { n -> n > 0 } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Suno limits") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Defaults are Suno v6's limits as of October 2026. Change them if Suno does.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(style, { style = it.filter(Char::isDigit) }, label = { Text("Style characters") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(lyrics, { lyrics = it.filter(Char::isDigit) }, label = { Text("Lyrics characters") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(words, { words = it.filter(Char::isDigit) }, label = { Text("Words per bracket tag") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                TextButton(onClick = { SunoLimits().let { style = it.styleChars.toString(); lyrics = it.lyricsChars.toString(); words = it.tagWords.toString() } }) { Text("Reset to defaults") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(SunoLimits(parsed[0]!!, parsed[1]!!, parsed[2]!!)) }, enabled = parsed.all { it != null }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NavItem(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
