package app.sunosongwriter.ui.participants

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.AppContainer
import app.sunosongwriter.data.presets.BackendPreset
import app.sunosongwriter.data.repo.BackendRepository
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.BackendKind
import app.sunosongwriter.domain.model.ModelRef
import app.sunosongwriter.domain.model.Participant
import app.sunosongwriter.domain.model.Role
import app.sunosongwriter.domain.model.StarterTeam
import app.sunosongwriter.domain.model.TeamSeat
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.backends.BackendGroup
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.CostLabel
import app.sunosongwriter.ui.components.Tag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One backend as the team builder sees it: the model it would use, or why it can't join. */
data class SeatRow(
    val backend: Backend,
    val name: String,
    val avatar: String,
    val modelLabel: String?,
    val seat: TeamSeat?,
    val isFree: Boolean?,
    /** Why it can't join, or null. */
    val problem: String?,
    /** Participants already using this backend. */
    val usedBy: List<String>,
    val selected: Boolean,
    val role: Role?,
)

data class TeamBuilderUi(
    val rows: List<SeatRow>,
    /** Chat-app presets not added yet: they need no key, so they can be added right here. */
    val chatApps: List<BackendPreset>,
    val freeOnly: Boolean,
    val built: List<String>? = null,
) {
    val selected: List<SeatRow> get() = rows.filter { it.selected && it.seat != null }
}

/**
 * Builds the team from the backends the user has added (spec 5.2): one participant per backend, with a
 * good model and a role. Keys and addresses are only ever entered on backends, never here.
 */
class TeamBuilderViewModel(private val c: AppContainer) : ViewModel() {
    /** The user's ticks, by backend id; unticked backends default to "on" unless already in the team. */
    private val overrides = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    private val built = MutableStateFlow<List<String>?>(null)

    val ui = combine(
        combine(c.backends.backends, c.backends.allModels, ::Pair),
        c.participants.participants,
        c.settings.freeOnly,
        overrides,
        built,
    ) { (backends, models), participants, freeOnly, picks, done ->
        val candidates = backends.map { b ->
            val usedBy = participants.filter { it.model?.backendId == b.id }.map { it.name }
            val mine = models.filter { it.backendId == b.id }
            val model = if (b.isRelay) null else StarterTeam.pickModel(b.presetId, mine, b, freeOnly)
            val seat = when {
                b.isRelay -> TeamSeat(b.id, b.presetId, isRelay = true, ModelRef(b.id, BackendRepository.RELAY_MODEL_ID))
                model != null -> TeamSeat(b.id, b.presetId, isRelay = false, ModelRef(b.id, model.modelId))
                else -> null
            }
            val problem = when {
                seat != null -> null
                mine.none { it.available } -> "No models listed yet. Open it in Backends and test the connection."
                freeOnly -> "No free chat model. Turn off free-only mode to use its paid models."
                else -> "No chat model listed."
            }
            val selected = seat != null && (picks[b.id] ?: usedBy.isEmpty())
            SeatRow(
                backend = b, name = StarterTeam.participantName(b), avatar = StarterTeam.avatar(b),
                modelLabel = if (b.isRelay) "You relay prompts by hand" else model?.displayName,
                seat = seat, isFree = if (b.isRelay) true else model?.let { b.isFree(it) }, problem = problem,
                usedBy = usedBy, selected = selected, role = null,
            )
        }.sortedBy { BackendGroup.of(it.backend).ordinal }
        val roles = StarterTeam.assignRoles(candidates.filter { it.selected }.mapNotNull { it.seat })
        TeamBuilderUi(
            rows = candidates.map { it.copy(role = roles[it.backend.id]) },
            chatApps = c.presets.presets.filter { p -> p.kind == BackendKind.RELAY && p.id != "relay-custom" && backends.none { it.presetId == p.id } },
            freeOnly = freeOnly,
            built = done,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggle(row: SeatRow) = overrides.update { it + (row.backend.id to !row.selected) }

    /** Chat apps need no key or address, so adding one is a single tap. It appears under Backends too. */
    fun addChatApp(preset: BackendPreset) = viewModelScope.launch {
        val id = c.backends.save(c.backends.draftFrom(preset), null)
        c.backends.refreshModels(id)
    }

    fun build() {
        val state = ui.value ?: return
        val seats = state.selected.mapNotNull { it.seat }
        viewModelScope.launch {
            val taken = c.participants.participants.first().map { it.name }.toMutableList()
            val report = state.selected.map { row ->
                val seat = row.seat!!
                val name = StarterTeam.uniqueName(row.name, taken).also { taken += it }
                c.participants.save(
                    Participant(
                        id = 0, name = name, avatar = row.avatar, defaultRole = row.role,
                        model = seat.model, fallbacks = StarterTeam.fallbacksFor(seat, seats),
                    ),
                )
                "${row.avatar} $name: ${row.modelLabel}" + (row.role?.takeIf { it != Role.REVIEWER }?.let { ", ${it.label.lowercase()}" } ?: "")
            }
            built.value = report
            overrides.value = emptyMap()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StarterTeamScreen(onDone: () -> Unit, onAddBackend: () -> Unit, onOpenBackend: (Long) -> Unit) {
    val vm = appViewModel { c, _ -> TeamBuilderViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(topBar = { BackTopBar("Build your team", onDone) }) { padding ->
        val state = ui ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.built?.let { report ->
                Text("Added to your team", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                report.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                return@Column
            }
            Text(
                "Your team is made from your backends: one participant each, using a good ${if (state.freeOnly) "free " else ""}model, with roles assigned for you. Keys and addresses stay on the backends, so there's nothing to enter here.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (state.rows.isEmpty()) {
                Text("You haven't added any backends yet. Add Ollama on your computer, a free API key (Gemini, Groq, OpenRouter), or a chat app below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.rows.forEach { row -> SeatRowView(row, onToggle = { vm.toggle(row) }, onOpen = { onOpenBackend(row.backend.id) }) }

            OutlinedButton(onClick = onAddBackend, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null)
                Text("Add a backend", modifier = Modifier.padding(start = 8.dp))
            }

            if (state.chatApps.isNotEmpty()) {
                Text("Chat apps you use (no key needed)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text("Free plans of chat apps can join too: you copy each prompt across and paste the reply back.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.chatApps.forEach { p ->
                        AssistChip(onClick = { vm.addChatApp(p) }, label = { Text(p.name.removeSuffix(" (relay)")) }, leadingIcon = { Icon(Icons.Filled.Add, null) })
                    }
                }
            }

            val n = state.selected.size
            Button(onClick = vm::build, enabled = n > 0, modifier = Modifier.fillMaxWidth()) {
                Text(if (n == 0) "Tick at least one backend" else "Add $n participant${if (n == 1) "" else "s"}")
            }
        }
    }
}

@Composable
private fun SeatRowView(row: SeatRow, onToggle: () -> Unit, onOpen: () -> Unit) {
    val usable = row.seat != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = if (usable) onToggle else onOpen),
    ) {
        Checkbox(checked = row.selected, onCheckedChange = { onToggle() }, enabled = usable)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${row.avatar} ${row.name}", style = MaterialTheme.typography.bodyLarge)
            Text(row.backend.name + (row.modelLabel?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            row.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (row.usedBy.isNotEmpty()) {
                Text("Already in your team as ${row.usedBy.joinToString()}" + if (row.selected) ": this adds another" else "", style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (row.selected) row.role?.let { Tag(it.label) }
            if (row.backend.isRelay) Tag("Relay") else row.isFree?.let { CostLabel(it) }
        }
    }
}
