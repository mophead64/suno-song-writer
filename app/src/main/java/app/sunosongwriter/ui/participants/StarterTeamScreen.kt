package app.sunosongwriter.ui.participants

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.AppContainer
import app.sunosongwriter.data.llm.LlmException
import app.sunosongwriter.domain.model.ModelRef
import app.sunosongwriter.domain.model.Participant
import app.sunosongwriter.domain.model.StarterSource
import app.sunosongwriter.domain.model.StarterTeam
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.rememberLocalNetworkGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SourceChoice(val source: StarterSource, val selected: Boolean, val key: String = "", val url: String = "")

data class StarterUi(
    val choices: List<SourceChoice>,
    val building: Boolean = false,
    val results: List<String> = emptyList(),
    val done: Boolean = false,
) {
    val canBuild: Boolean get() = !building && choices.any { it.selected && (!it.source.needsKey || it.key.isNotBlank()) }
}

/** First-run wizard: a team built only from free presets (spec 5.2). */
class StarterTeamViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(
        StarterUi(
            StarterTeam.sources.map { s ->
                SourceChoice(s, selected = s.presetId == "ollama-local" || s.presetId == "relay-claude", url = c.presets.find(s.presetId)?.baseUrl.orEmpty())
            },
        ),
    )
    val ui = _ui.asStateFlow()

    fun edit(presetId: String, change: (SourceChoice) -> SourceChoice) =
        _ui.update { u -> u.copy(choices = u.choices.map { if (it.source.presetId == presetId) change(it) else it }) }

    fun build() {
        val chosen = _ui.value.choices.filter { it.selected && (!it.source.needsKey || it.key.isNotBlank()) }
        _ui.update { it.copy(building = true, results = emptyList()) }
        viewModelScope.launch {
            val refs = linkedMapOf<String, ModelRef>()
            val report = mutableListOf<String>()
            val existing = c.backends.backends.first()
            for (choice in chosen) {
                val preset = c.presets.find(choice.source.presetId) ?: continue
                val name = choice.source.participantName
                val backendId = existing.firstOrNull { it.presetId == preset.id }?.id ?: run {
                    var draft = c.backends.draftFrom(preset)
                    if (choice.source.isLocal && choice.url.isNotBlank()) draft = draft.copy(baseUrl = choice.url.trim())
                    c.backends.save(draft, choice.key.trim().ifEmpty { null })
                }
                val refreshed = c.backends.refreshModels(backendId)
                refreshed.exceptionOrNull()?.let { e ->
                    report += "$name: couldn't connect (${(e as? LlmException)?.error?.message ?: e.message}). The backend was saved; fix it in Settings → Backends."
                    continue
                }
                val backend = c.backends.get(backendId) ?: continue
                val model = StarterTeam.pickModel(preset.id, c.backends.observeModels(backendId).first(), backend)
                if (model == null) {
                    report += "$name: connected, but no free chat model was listed."
                    continue
                }
                refs[preset.id] = ModelRef(backendId, model.modelId)
                report += "$name: ${model.displayName}"
            }
            val roles = StarterTeam.assignRoles(refs.keys.toList())
            val taken = c.participants.participants.first().map { it.name.lowercase() }.toSet()
            for ((presetId, ref) in refs) {
                val source = StarterTeam.sources.first { it.presetId == presetId }
                if (source.participantName.lowercase() in taken) continue
                c.participants.save(
                    Participant(
                        id = 0,
                        name = source.participantName,
                        avatar = source.avatar,
                        defaultRole = roles[presetId],
                        model = ref,
                        fallbacks = if (source.isRelay) emptyList() else StarterTeam.fallbacksFor(presetId, refs.filterKeys { !it.startsWith("relay-") }),
                    ),
                )
            }
            report += roles.entries.filter { it.key in refs }.joinToString(" · ") { (id, role) -> "${StarterTeam.sources.first { it.presetId == id }.participantName}: ${role.label}" }
            _ui.update { it.copy(building = false, results = report, done = true) }
        }
    }
}

@Composable
fun StarterTeamScreen(onDone: () -> Unit) {
    val vm = appViewModel { c, _ -> StarterTeamViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val withLocalNetwork = rememberLocalNetworkGate(onDenied = {})
    val uri = LocalUriHandler.current

    Scaffold(topBar = { BackTopBar("Free starter team", onDone) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Tick the free sources you have. Each one becomes a participant with a free model, and the strongest writers become your lyricist and implementer. No payment details anywhere.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ui.choices.forEach { choice ->
                val s = choice.source
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vm.edit(s.presetId) { it.copy(selected = !it.selected) } }) {
                    Checkbox(checked = choice.selected, onCheckedChange = { v -> vm.edit(s.presetId) { it.copy(selected = v) } })
                    Column(Modifier.weight(1f)) {
                        Text("${s.avatar} ${s.participantName}")
                        Text(
                            when {
                                s.isRelay -> "Free chat app: you relay prompts by hand"
                                s.isLocal -> "Runs on your computer"
                                else -> "Free API tier: needs a free key"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (choice.selected && s.needsKey) {
                    OutlinedTextField(
                        value = choice.key,
                        onValueChange = { v -> vm.edit(s.presetId) { it.copy(key = v) } },
                        label = { Text("${s.participantName} API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
                    )
                    val signup = mapOf("gemini" to "https://aistudio.google.com/apikey", "openrouter" to "https://openrouter.ai/keys", "groq" to "https://console.groq.com/keys")[s.presetId]
                    signup?.let { url -> TextButton(onClick = { uri.openUri(url) }, modifier = Modifier.padding(start = 40.dp)) { Text("Get a free key") } }
                }
                if (choice.selected && s.isLocal) {
                    OutlinedTextField(
                        value = choice.url,
                        onValueChange = { v -> vm.edit(s.presetId) { it.copy(url = v) } },
                        label = { Text("Ollama address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
                    )
                }
            }
            if (ui.done) {
                Text("Your team", style = MaterialTheme.typography.titleMedium)
                ui.results.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            } else {
                Button(
                    onClick = {
                        val local = ui.choices.firstOrNull { it.selected && it.source.isLocal }?.url.orEmpty()
                        withLocalNetwork(local) { vm.build() }
                    },
                    enabled = ui.canBuild,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (ui.building) CircularProgressIndicator(Modifier.size(20.dp)) else Text("Build my team")
                }
            }
        }
    }
}
