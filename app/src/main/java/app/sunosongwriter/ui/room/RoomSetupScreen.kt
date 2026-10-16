package app.sunosongwriter.ui.room

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sunosongwriter.AppContainer
import app.sunosongwriter.domain.model.Role
import app.sunosongwriter.domain.usage.RequestsLeft
import app.sunosongwriter.domain.workflow.RoomConfig
import app.sunosongwriter.domain.workflow.ChairMode
import app.sunosongwriter.domain.workflow.WritersRoom
import app.sunosongwriter.ui.RoomSetupRoute
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.CostLabel
import app.sunosongwriter.ui.components.LocalNetworkAccess
import app.sunosongwriter.ui.components.Tag
import app.sunosongwriter.ui.components.describe
import app.sunosongwriter.ui.components.requestsLeft
import app.sunosongwriter.ui.components.rememberLocalNetworkGate
import app.sunosongwriter.ui.song.Picker
import app.sunosongwriter.ui.song.TeamMember
import app.sunosongwriter.ui.song.observeTeam
import app.sunosongwriter.domain.usage.CostEstimate
import app.sunosongwriter.domain.usage.Price
import app.sunosongwriter.domain.usage.SpendCheck
import app.sunosongwriter.domain.usage.SpendLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The run's settings while being edited. Null ids mean "skip that step". */
data class RoomForm(
    val reviewerIds: Set<Long>,
    val lyricistId: Long?,
    val proposeStyles: Boolean,
    val implementerId: Long?,
    val finaliserId: Long?,
    val roundCap: Int = 3,
    val chair: ChairMode = ChairMode.YOU,
    val autoAcceptBackers: Int = 2,
    val chairId: Long? = null,
) {
    fun toConfig(): RoomConfig? = implementerId?.let {
        RoomConfig(reviewerIds.toList(), lyricistId, proposeStyles, it, finaliserId, roundCap, chair, autoAcceptBackers, chairId.takeIf { chair == ChairMode.MODEL })
    }
}

/** One participant's line in the pre-run check (spec 5.2). */
data class CheckLine(val member: TeamMember, val requests: Int, val left: RequestsLeft?, val price: Price? = null) {
    val shortfall: Boolean get() = left != null && left.remaining < requests
    val isPaid: Boolean get() = member.isFree == false && !member.isRelay
}

data class RoomSetupUi(
    val members: List<TeamMember>,
    val form: RoomForm,
    val hasLyrics: Boolean,
    val check: List<CheckLine>,
    val freeOnly: Boolean,
    val monthSpendUsd: Double = 0.0,
    val limits: SpendLimits = SpendLimits(),
) {
    /** Estimated cost of the paid participants with a price, low to high; null if none are priced. */
    val costRange: ClosedFloatingPointRange<Double>?
        get() = check.filter { it.isPaid && it.price != null }
            .map { CostEstimate.range(it.requests, it.price!!) }
            .reduceOrNull { a, b -> (a.start + b.start)..(a.endInclusive + b.endInclusive) }
    val unpriced: List<CheckLine> get() = check.filter { it.isPaid && it.price == null }
    val blocked: List<TeamMember> get() = check.map { it.member }.filter { it.blockedReason != null }
    val relayPrompts: Int get() = check.filter { it.member.isRelay }.sumOf { it.requests }
    val canStart: Boolean
        get() = form.reviewerIds.isNotEmpty() && form.implementerId != null && blocked.isEmpty() && (form.chair != ChairMode.MODEL || form.chairId != null)
}

class RoomSetupViewModel(savedState: SavedStateHandle, private val c: AppContainer) : ViewModel() {
    private val route = savedState.toRoute<RoomSetupRoute>()
    private val form = MutableStateFlow<RoomForm?>(null)
    private val _started = MutableStateFlow<Long?>(null)
    val started = _started.asStateFlow()

    val ui = combine(observeTeam(c), form, c.requestCounts.observeRecent(), c.backends.backends, c.settings.freeOnly) { members, f, counts, backends, freeOnly ->
        val current = c.songs.latest(route.variantId)
        val hasLyrics = current?.content?.lyrics?.isNotBlank() == true
        val formNow = f ?: defaults(members, hasLyrics).also { form.value = it }
        val config = formNow.toConfig()
        val estimates = config?.let(WritersRoom::estimateRequests).orEmpty()
        val models = c.backends.allModels.first()
        val check = estimates.mapNotNull { (id, n) ->
            val member = members.firstOrNull { it.participant.id == id } ?: return@mapNotNull null
            val left = member.participant.model?.let { ref ->
                requestsLeft(ref, backends, counts, c.quotas, c.clock(), models.firstOrNull { it.backendId == ref.backendId && it.modelId == ref.modelId }?.pricedFree)
            }
            CheckLine(member, n, left)
        }
        RoomSetupUi(members, formNow, hasLyrics, check, freeOnly)
    }.combine(combine(c.usage.prices, c.usage.observeMonthSpend(), c.settings.spendLimits, ::Triple)) { ui, (prices, month, limits) ->
        ui?.copy(
            check = ui.check.map { line -> line.copy(price = line.member.participant.model?.let { ref -> prices.firstOrNull { it.backendId == ref.backendId && it.modelId == ref.modelId } }) },
            monthSpendUsd = month,
            limits = limits,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Sensible defaults from participants' roles (spec 5.5; open questions Q1–Q4 default to these). */
    private fun defaults(members: List<TeamMember>, hasLyrics: Boolean): RoomForm {
        val ok = members.filter { it.blockedReason == null }
        fun byRole(role: Role) = ok.firstOrNull { it.participant.defaultRole == role }?.participant?.id
        val firstApi = ok.firstOrNull { !it.isRelay }?.participant?.id
        val implementer = byRole(Role.IMPLEMENTER) ?: byRole(Role.LYRICIST) ?: firstApi ?: ok.firstOrNull()?.participant?.id
        return RoomForm(
            reviewerIds = ok.map { it.participant.id }.toSet(),
            lyricistId = if (hasLyrics) null else (byRole(Role.LYRICIST) ?: firstApi),
            proposeStyles = true,
            implementerId = implementer,
            finaliserId = byRole(Role.FINALISER) ?: implementer,
        )
    }

    fun edit(change: (RoomForm) -> RoomForm) = form.update { it?.let(change) }

    fun start() {
        val config = ui.value?.form?.toConfig() ?: return
        viewModelScope.launch {
            val sessionId = c.room.create(route.songId, route.variantId, config)
            c.roomScheduler.kick(sessionId)
            _started.value = sessionId
        }
    }
}

@Composable
fun RoomSetupScreen(onBack: () -> Unit, onStarted: (Long) -> Unit, onOpenStarterTeam: () -> Unit) {
    val vm = appViewModel { c, saved -> RoomSetupViewModel(saved, c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val started by vm.started.collectAsStateWithLifecycle()
    LaunchedEffect(started) { started?.let(onStarted) }
    // Ask once for notifications, so the chair hears when it's their turn (Android 13+).
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val withLocalNetwork = rememberLocalNetworkGate(onDenied = {})

    Scaffold(topBar = { BackTopBar("Writers' Room", onBack) }) { padding ->
        val state = ui ?: return@Scaffold
        val f = state.form
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.members.isEmpty()) {
                Text("You need a team first: a few participants, each a model from one of your backends with a name and role.")
                Button(onClick = onOpenStarterTeam) { Text("Build your team") }
                return@Column
            }
            Text(
                "Your team writes, proposes styles, reviews in rounds and implements what the chair accepts. You can chair it yourself, or let it run on its own.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Reviewers", style = MaterialTheme.typography.titleMedium)
            state.members.forEach { m ->
                val id = m.participant.id
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { vm.edit { it.copy(reviewerIds = it.reviewerIds.toggle(id)) } },
                ) {
                    Checkbox(checked = id in f.reviewerIds, onCheckedChange = { vm.edit { it.copy(reviewerIds = it.reviewerIds.toggle(id)) } })
                    Text("${m.participant.avatar} ${m.participant.name}", modifier = Modifier.weight(1f))
                    if (m.isRelay) Tag("Relay") else m.isFree?.let { CostLabel(it) }
                }
            }

            val choices = state.members.map { "${it.participant.avatar} ${it.participant.name}" to it.participant.id as Long? }
            fun name(id: Long?) = state.members.firstOrNull { it.participant.id == id }?.let { "${it.participant.avatar} ${it.participant.name}" }
            Picker("Lyricist (writes base lyrics first)", name(f.lyricistId) ?: "Skip: keep the current lyrics", listOf("Skip: keep the current lyrics" to null) + choices) { id -> vm.edit { it.copy(lyricistId = id) } }
            if (state.hasLyrics && f.lyricistId != null) Text("This will replace the song's current lyrics in the Consensus variant.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vm.edit { it.copy(proposeStyles = !it.proposeStyles) } }) {
                Column(Modifier.weight(1f)) {
                    Text("Style proposals")
                    Text("Each reviewer proposes a style prompt and you pick one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = f.proposeStyles, onCheckedChange = { v -> vm.edit { it.copy(proposeStyles = v) } })
            }
            Picker("Implementer (applies accepted suggestions)", name(f.implementerId) ?: "Choose", choices) { id -> vm.edit { it.copy(implementerId = id) } }
            Picker("Finaliser (Suno v6 guide → Framework-refined)", name(f.finaliserId) ?: "Skip finalising", listOf("Skip finalising" to null) + choices) { id -> vm.edit { it.copy(finaliserId = id) } }

            Text("Review rounds: at most ${f.roundCap}", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { n ->
                    if (n == f.roundCap) Button(onClick = {}) { Text("$n") } else OutlinedButton(onClick = { vm.edit { it.copy(roundCap = n) } }) { Text("$n") }
                }
            }
            ChairSection(f, state.members, choices, ::name) { change -> vm.edit(change) }
            Text(
                "The debate stops early when every reviewer approves, or when you end it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PreRunCheck(state)

            Button(
                onClick = {
                    val chosen = f.reviewerIds + listOfNotNull(f.lyricistId, f.implementerId, f.finaliserId)
                    val localUrl = state.members.filter { it.participant.id in chosen }.mapNotNull { it.baseUrl }.firstOrNull(LocalNetworkAccess::isLocalUrl)
                    withLocalNetwork(localUrl.orEmpty()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
                    }
                },
                enabled = state.canStart,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start the Writers' Room") }
        }
    }
}

/** Requests per participant against what they have left today, relay load, and cost (spec 5.2, 5.17). */
@Composable
private fun PreRunCheck(state: RoomSetupUi) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Before you start", style = MaterialTheme.typography.titleMedium)
            state.check.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${line.member.participant.avatar} ${line.member.participant.name}", modifier = Modifier.weight(1f))
                    Text(
                        "up to ${line.requests} " + when {
                            line.member.isRelay -> "prompts to relay"
                            line.left != null -> "of ${line.left.describe().substringBefore(" requests")} left"
                            else -> "requests"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (line.shortfall) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.check.any { it.shortfall }) {
                Text(
                    "Some participants may run out of today's free requests. Their fallbacks take over if they have any; otherwise the run pauses until the quota resets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.relayPrompts > 0) Text("You'll relay up to ${state.relayPrompts} prompts by hand.", style = MaterialTheme.typography.bodySmall)
            state.blocked.forEach { Text("${it.participant.name}: ${it.blockedReason}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val allFree = state.check.all { it.member.isFree == true || it.member.isRelay }
            Text(
                when {
                    state.freeOnly -> "Free-only mode is on: this run costs \$0."
                    allFree -> "Every participant is free: this run costs \$0."
                    else -> state.costRange?.let { "Estimated cost: ${SpendCheck.format(it.start)}–${SpendCheck.format(it.endInclusive)}" + if (state.unpriced.isEmpty()) "." else ", plus unpriced participants." }
                        ?: "Some participants use paid models with no price in the price table, so the cost can't be estimated."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!state.freeOnly && !allFree) {
                state.unpriced.forEach {
                    Text("${it.member.participant.name}: add its model to Settings → Usage and limits → Price table for an estimate. OpenRouter reports the real cost after each step.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.limits.monthlyUsd?.let { limit ->
                    val left = (limit - state.monthSpendUsd).coerceAtLeast(0.0)
                    val tight = state.costRange?.let { it.endInclusive > left } == true
                    Text(
                        "${SpendCheck.format(left)} left of this month's ${SpendCheck.format(limit)} limit." + if (tight) " The run may hit it and pause." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

/** Who decides at the style pick and after each round (spec 5.5, auto chair). */
@Composable
private fun ChairSection(
    f: RoomForm,
    members: List<TeamMember>,
    choices: List<Pair<String, Long?>>,
    name: (Long?) -> String?,
    edit: ((RoomForm) -> RoomForm) -> Unit,
) {
    Text("Chair", style = MaterialTheme.typography.titleMedium)
    Picker(
        "Who decides",
        when (f.chair) {
            ChairMode.YOU -> "You: the run pauses for your decisions"
            ChairMode.AUTO -> "Automatic: runs without you"
            ChairMode.MODEL -> "A participant decides"
        },
        listOf(
            "You: the run pauses for your decisions" to ChairMode.YOU,
            "Automatic: runs without you" to ChairMode.AUTO,
            "A participant decides" to ChairMode.MODEL,
        ),
    ) { mode -> edit { it.copy(chair = mode) } }
    when (f.chair) {
        ChairMode.YOU -> Unit
        ChairMode.AUTO -> {
            val reviewers = f.reviewerIds.size.coerceAtLeast(1)
            Text(
                run {
                    val n = minOf(f.autoAcceptBackers, reviewers)
                    "Picks the most detailed style proposal, and accepts suggestions " +
                        (if (n == 1) "from any reviewer" else "that at least $n reviewers agree on") + ". You can take over the chair at any time."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (reviewers > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Backed by at least", style = MaterialTheme.typography.bodySmall)
                    (1..minOf(reviewers, 5)).forEach { n ->
                        if (n == f.autoAcceptBackers) Button(onClick = {}) { Text("$n") } else OutlinedButton(onClick = { edit { it.copy(autoAcceptBackers = n) } }) { Text("$n") }
                    }
                }
            }
        }
        ChairMode.MODEL -> {
            Picker("Chair participant", name(f.chairId) ?: "Choose", choices.filter { it.second != null }) { id -> edit { it.copy(chairId = id) } }
            Text(
                "They see the proposals or suggestions and decide in one step per round. If their reply can't be read, the run waits for you. You can take over at any time.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (members.firstOrNull { it.participant.id == f.chairId }?.isRelay == true) {
                Text("A relay chair means relaying their prompt each round, so the run won't be hands-free.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}
