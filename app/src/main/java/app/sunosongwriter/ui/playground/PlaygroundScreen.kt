package app.sunosongwriter.ui.playground

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.AppContainer
import app.sunosongwriter.data.llm.ChatEvent
import app.sunosongwriter.data.llm.ChatMessage
import app.sunosongwriter.domain.model.Participant
import app.sunosongwriter.domain.usage.RequestsLeft
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.CostLabel
import app.sunosongwriter.ui.components.RequestsLeftText
import app.sunosongwriter.ui.components.buildModelOptions
import app.sunosongwriter.ui.components.describeRef
import app.sunosongwriter.ui.components.rememberLocalNetworkGate
import app.sunosongwriter.ui.components.requestsLeft
import app.sunosongwriter.ui.participants.Avatar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaygroundTarget(
    val participant: Participant,
    val modelLabel: String,
    val isFree: Boolean?,
    val requestsLeft: RequestsLeft?,
    val baseUrl: String?,
)

data class PlaygroundRun(
    val reply: String = "",
    val running: Boolean = false,
    val waiting: String? = null,
    val error: String? = null,
    val usage: String? = null,
    val finished: Boolean = false,
)

/** Debug screen from iteration 1: send a prompt to one participant through [app.sunosongwriter.data.llm.ChatGateway]. */
class PlaygroundViewModel(private val c: AppContainer) : ViewModel() {
    val targets = combine(
        c.participants.participants,
        c.backends.backends,
        c.backends.allModels,
        c.requestCounts.observeRecent(),
        c.quotas.changes,
    ) { participants, backends, models, counts, _ ->
        val options = buildModelOptions(backends, models)
        participants.filter { it.model != null }.map { p ->
            val option = options.firstOrNull { it.ref == p.model }
            PlaygroundTarget(
                participant = p,
                modelLabel = describeRef(p.model, options, backends),
                isFree = option?.isFree,
                requestsLeft = requestsLeft(p.model!!, backends, counts, c.quotas, c.clock(), option?.model?.pricedFree),
                baseUrl = backends.firstOrNull { it.id == p.model.backendId }?.baseUrl,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId = _selectedId.asStateFlow()
    private val _run = MutableStateFlow(PlaygroundRun())
    val run = _run.asStateFlow()
    private var job: Job? = null

    fun select(id: Long) {
        _selectedId.value = id
    }

    fun send(prompt: String) {
        val target = targets.value?.let { list -> list.firstOrNull { it.participant.id == _selectedId.value } ?: list.firstOrNull() } ?: return
        val ref = target.participant.model ?: return
        job?.cancel()
        _run.value = PlaygroundRun(running = true)
        job = viewModelScope.launch {
            c.gateway.stream(ref, listOf(ChatMessage(ChatMessage.Role.USER, prompt))).collect { event ->
                _run.update { run ->
                    when (event) {
                        is ChatEvent.Delta -> run.copy(reply = run.reply + event.text, waiting = null)
                        is ChatEvent.RateLimited -> run.copy(waiting = "Rate limited, waiting ~${event.waitSeconds}s" + if (event.attempt > 0) " (retry ${event.attempt})" else "")
                        is ChatEvent.Usage -> run.copy(usage = describeUsage(event))
                        is ChatEvent.Completed -> run.copy(running = false, waiting = null, finished = true)
                        is ChatEvent.Failed -> run.copy(running = false, waiting = null, error = event.error.message)
                    }
                }
            }
            _run.update { it.copy(running = false, waiting = null) }
        }
    }

    fun localNetworkDenied() {
        _run.value = PlaygroundRun(error = "Local network access was refused, so local servers can't be reached. Allow it in the app's settings under Nearby devices.")
    }

    fun stop() {
        job?.cancel()
        _run.update { it.copy(running = false, waiting = null, error = it.error ?: "Stopped.") }
    }

    private fun describeUsage(u: ChatEvent.Usage): String = listOfNotNull(
        u.inputTokens?.let { "$it tokens in" },
        u.outputTokens?.let { "$it out" },
        u.costUsd?.let { if (it == 0.0) "free" else "$" + "%.5f".format(it) },
    ).joinToString(" · ")
}

@Composable
fun PlaygroundScreen(onBack: () -> Unit, onOpenParticipants: () -> Unit) {
    val vm = appViewModel { c, _ -> PlaygroundViewModel(c) }
    val targets by vm.targets.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    var prompt by remember { mutableStateOf("Write a four-line chorus about rain on a tin roof.") }
    val withLocalNetwork = rememberLocalNetworkGate(onDenied = vm::localNetworkDenied)

    Scaffold(topBar = { BackTopBar("Playground", onBack) }) { padding ->
        val list = targets ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (list.isEmpty()) {
                Text("Add a participant with a model first.")
                Button(onClick = onOpenParticipants) { Text("Participants") }
                return@Column
            }
            val selected = list.firstOrNull { it.participant.id == selectedId } ?: list.first()
            TargetPicker(list, selected, vm::select)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected.modelLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
                selected.isFree?.let { CostLabel(it) }
            }
            RequestsLeftText(selected.requestsLeft)

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Prompt") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { withLocalNetwork(selected.baseUrl.orEmpty()) { vm.send(prompt) } }, enabled = !run.running && prompt.isNotBlank()) { Text("Send") }
                if (run.running) {
                    TextButton(onClick = vm::stop) { Text("Stop") }
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }

            run.waiting?.let {
                Text(it, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            run.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (run.reply.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(selected.participant.avatar)
                            Text(selected.participant.name, style = MaterialTheme.typography.titleSmall)
                        }
                        SelectionContainer { Text(run.reply) }
                    }
                }
            }
            run.usage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun TargetPicker(list: List<PlaygroundTarget>, selected: PlaygroundTarget, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("${selected.participant.avatar} ${selected.participant.name}")
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose participant")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            list.forEach { t ->
                DropdownMenuItem(
                    text = { Text("${t.participant.avatar} ${t.participant.name}") },
                    onClick = { onSelect(t.participant.id); open = false },
                )
            }
        }
    }
}
