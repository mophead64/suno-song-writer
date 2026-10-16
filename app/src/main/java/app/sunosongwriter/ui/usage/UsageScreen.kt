package app.sunosongwriter.ui.usage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.AppContainer
import app.sunosongwriter.data.llm.OpenRouterCredit
import app.sunosongwriter.data.repo.BackendRepository
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.usage.PeriodUsage
import app.sunosongwriter.domain.usage.SpendCheck
import app.sunosongwriter.domain.usage.SpendLevel
import app.sunosongwriter.domain.usage.SpendLimits
import app.sunosongwriter.domain.usage.UsageRecord
import app.sunosongwriter.domain.usage.UsageSummary
import app.sunosongwriter.domain.usage.UsageTotals
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.backends.BackendGroup
import app.sunosongwriter.ui.components.BackTopBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** OpenRouter credit as fetched, or why it couldn't be. */
data class CreditState(val backend: Backend, val credit: OpenRouterCredit? = null, val error: String? = null, val loading: Boolean = false)

data class UsageUi(
    val records: List<UsageRecord>,
    val limits: SpendLimits,
    val freeOnly: Boolean,
    val monthSpend: Double,
    /** Paid-capable API backends, for "remaining credit". */
    val apiBackends: List<Backend>,
    val songTitles: Map<Long, String>,
)

class UsageViewModel(private val c: AppContainer) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    private val since = LocalDate.now(zone).withDayOfMonth(1).minusMonths(11).atStartOfDay(zone).toInstant().toEpochMilli()
    private val _credits = MutableStateFlow<Map<Long, CreditState>>(emptyMap())
    val credits = _credits.asStateFlow()

    val ui = combine(
        c.usage.observeSince(since),
        combine(c.settings.spendLimits, c.settings.freeOnly, c.usage.observeMonthSpend()) { l, f, m -> Triple(l, f, m) },
        c.backends.backends,
        c.songs.songs,
    ) { records, (limits, freeOnly, month), backends, songs ->
        UsageUi(
            records = records,
            limits = limits,
            freeOnly = freeOnly,
            monthSpend = month,
            apiBackends = backends.filter { !it.isRelay && !it.isLocal },
            songTitles = songs.associate { it.id to it.title.ifBlank { "Untitled" } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun days(ui: UsageUi): List<PeriodUsage> = UsageSummary.byDay(ui.records, LocalDate.now(zone), zone)
    fun months(ui: UsageUi): List<PeriodUsage> = UsageSummary.byMonth(ui.records, LocalDate.now(zone), zone)

    /** This month's usage per song. */
    fun songsThisMonth(ui: UsageUi): List<Pair<Long?, UsageTotals>> {
        val start = UsageSummary.monthStartMillis(System.currentTimeMillis(), zone)
        return UsageSummary.bySong(ui.records.filter { it.at >= start })
    }

    fun setLimits(limits: SpendLimits) = viewModelScope.launch { c.settings.setSpendLimits(limits) }

    fun loadCredit(backend: Backend) {
        _credits.update { it + (backend.id to CreditState(backend, loading = true)) }
        viewModelScope.launch {
            val result = c.backends.openRouterCredit(backend.id)
            _credits.update {
                it + (backend.id to CreditState(backend, credit = result.getOrNull(), error = result.exceptionOrNull()?.message))
            }
        }
    }
}

private val Backend.isLocal: Boolean get() = BackendGroup.of(this) == BackendGroup.LOCAL

/** Usage and limits (spec 5.17): spend against limits, remaining credit, tokens and cost by day, month, backend and song. */
@Composable
fun UsageScreen(onBack: () -> Unit, onOpenPrices: () -> Unit) {
    val vm = appViewModel { c, _ -> UsageViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val credits by vm.credits.collectAsStateWithLifecycle()
    var byMonth by rememberSaveable { mutableStateOf(false) }
    var editLimits by remember { mutableStateOf(false) }

    Scaffold(topBar = { BackTopBar("Usage and limits", onBack) }) { padding ->
        val state = ui ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LimitsCard(state, onEdit = { editLimits = true })
            CreditCard(state.apiBackends, credits, onLoad = vm::loadCredit)

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !byMonth, onClick = { byMonth = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Last 14 days") }
                SegmentedButton(selected = byMonth, onClick = { byMonth = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Last 12 months") }
            }
            val periods = (if (byMonth) vm.months(state) else vm.days(state)).filter { it.totals.requests > 0 }
            if (periods.isEmpty()) {
                Text("No model requests yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            periods.forEach { PeriodCard(it, byMonth) }

            val songs = vm.songsThisMonth(state)
            if (songs.isNotEmpty()) {
                Text("By song this month", style = MaterialTheme.typography.titleMedium)
                songs.take(10).forEach { (id, totals) ->
                    UsageLine(id?.let { state.songTitles[it] ?: "Deleted song" } ?: "Not for a song (Playground)", totals)
                }
            }
            OutlinedButton(onClick = onOpenPrices) { Text("Price table") }
            Text(
                "Costs are reported by the provider where it can (OpenRouter), estimated from the price table otherwise, and \$0 for free models. Requests made before this version weren't recorded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (editLimits) {
        ui?.let { s -> LimitsDialog(s.limits, onDismiss = { editLimits = false }) { vm.setLimits(it); editLimits = false } }
    }
}

@Composable
private fun LimitsCard(state: UsageUi, onEdit: () -> Unit) {
    val limits = state.limits
    val level = SpendCheck.level(state.monthSpend, limits.monthlyUsd, limits.warnPercent)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (level) {
                SpendLevel.OK -> MaterialTheme.colorScheme.surfaceContainerHigh
                SpendLevel.WARN -> MaterialTheme.colorScheme.tertiaryContainer
                SpendLevel.REACHED -> MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Spending this month", style = MaterialTheme.typography.titleMedium)
            Text(
                SpendCheck.format(state.monthSpend) + (limits.monthlyUsd?.let { " of ${SpendCheck.format(it)}" } ?: ""),
                style = MaterialTheme.typography.headlineSmall,
            )
            limits.monthlyUsd?.let { limit ->
                LinearProgressIndicator(progress = { (state.monthSpend / limit).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                when (level) {
                    SpendLevel.REACHED -> "Monthly limit reached: paid requests are refused, and Writers' Rooms using them pause."
                    SpendLevel.WARN -> "Over ${limits.warnPercent}% of the monthly limit."
                    SpendLevel.OK -> if (state.freeOnly) "Free-only mode is on, so nothing can cost money." else if (!limits.isSet) "No spend limits set." else ""
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Monthly limit: ${limits.monthlyUsd?.let(SpendCheck::format) ?: "none"} · per run: ${limits.sessionUsd?.let(SpendCheck::format) ?: "none"} · warn at ${limits.warnPercent}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onEdit) { Text("Set limits") }
        }
    }
}

/** Remaining credit: OpenRouter has an API for it; other providers show "not available" rather than a guess. */
@Composable
private fun CreditCard(backends: List<Backend>, credits: Map<Long, CreditState>, onLoad: (Backend) -> Unit) {
    if (backends.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Remaining credit", style = MaterialTheme.typography.titleMedium)
            backends.forEach { b ->
                if (b.presetId != BackendRepository.OPENROUTER_PRESET) {
                    Text("${b.name}: not available (the provider has no API for it)", style = MaterialTheme.typography.bodySmall)
                    return@forEach
                }
                val state = credits[b.id]
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(b.name, style = MaterialTheme.typography.labelLarge)
                    when {
                        state == null -> TextButton(onClick = { onLoad(b) }) { Text("Check credit") }
                        state.loading -> Text("Checking…", style = MaterialTheme.typography.bodySmall)
                        state.error != null -> {
                            Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onLoad(b) }) { Text("Try again") }
                        }
                        else -> state.credit?.let { CreditLines(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditLines(c: OpenRouterCredit) {
    val lines = buildList {
        c.accountRemainingUsd?.let { add("Account: ${SpendCheck.format(it)} left of ${SpendCheck.format(c.accountPurchasedUsd ?: 0.0)} bought") }
        if (c.keyLimitUsd != null) add("This key: ${SpendCheck.format(c.keyRemainingUsd ?: 0.0)} left of its ${SpendCheck.format(c.keyLimitUsd)} limit")
        if (c.freeTier == true) add("Free tier: free models are limited to fewer requests a day until the account buys credit.")
        if (isEmpty()) add("OpenRouter didn't report any credit.")
    }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
}

private val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy")

@Composable
private fun PeriodCard(p: PeriodUsage, month: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            UsageLine((if (month) monthFormat else dayFormat).format(p.start), p.totals, heading = true)
            if (p.byBackend.size > 1) p.byBackend.forEach { (name, t) -> UsageLine(name, t) }
            else p.byBackend.singleOrNull()?.let { Text(it.first, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun UsageLine(label: String, t: UsageTotals, heading: Boolean = false) {
    val cost = buildString {
        append(SpendCheck.format(t.costUsd))
        if (t.estimatedUsd > 0) append(" (~${SpendCheck.format(t.estimatedUsd)} estimated)")
        if (t.unknownCost > 0) append(" + ${t.unknownCost} unpriced")
    }
    val detail = "${t.requests} request${if (t.requests == 1) "" else "s"} · ${"%,d".format(t.inputTokens)} in · ${"%,d".format(t.outputTokens)} out · $cost"
    Column(Modifier.clearAndSetSemantics { contentDescription = "$label: $detail" }) {
        Text(label, style = if (heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LimitsDialog(current: SpendLimits, onDismiss: () -> Unit, onSave: (SpendLimits) -> Unit) {
    var monthly by remember { mutableStateOf(current.monthlyUsd?.toString().orEmpty()) }
    var session by remember { mutableStateOf(current.sessionUsd?.toString().orEmpty()) }
    var warn by remember { mutableStateOf(current.warnPercent.toString()) }
    fun money(s: String) = s.trim().ifEmpty { null }?.toDoubleOrNull()
    val valid = (monthly.isBlank() || money(monthly) != null) && (session.isBlank() || money(session) != null) && warn.toIntOrNull() in 1..100
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Spend limits") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("For paid models only. At a limit, paid requests are refused and Writers' Rooms pause. Leave blank for no limit.", style = MaterialTheme.typography.bodySmall)
                val numeric = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                OutlinedTextField(monthly, { monthly = it }, label = { Text("Per month (US\$)") }, singleLine = true, keyboardOptions = numeric)
                OutlinedTextField(session, { session = it }, label = { Text("Per run or song session (US\$)") }, singleLine = true, keyboardOptions = numeric)
                OutlinedTextField(warn, { warn = it.filter(Char::isDigit) }, label = { Text("Warn at (% of the limit)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(SpendLimits(money(monthly), money(session), warn.toInt())) }, enabled = valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
