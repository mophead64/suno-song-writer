package app.sunosongwriter.ui.results

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.AppContainer
import app.sunosongwriter.domain.results.VariantStats
import app.sunosongwriter.domain.results.WinRates
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.math.roundToInt

data class WinRatesUi(val stats: List<VariantStats>, val tags: List<String>, val decidedSongs: Int)

class WinRatesViewModel(c: AppContainer) : ViewModel() {
    private val _tag = MutableStateFlow<String?>(null)
    val tag = _tag.asStateFlow()

    val ui = combine(c.snapshots.all, c.results.allTakes, c.results.winners, c.songs.songs, _tag) { snapshots, takes, winners, songs, tag ->
        val tags = songs.associate { it.id to it.tags }
        val sentSongs = snapshots.map { it.songId }.toSet()
        WinRatesUi(
            stats = WinRates.compute(snapshots, takes, winners, tags, tag),
            tags = songs.filter { it.id in sentSongs }.flatMap { it.tags }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() },
            decidedSongs = winners.keys.count { id -> tag == null || tags[id].orEmpty().any { it.equals(tag, ignoreCase = true) } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setTag(tag: String?) {
        _tag.value = tag
    }
}

/** Which variant tends to win, overall and per genre tag (spec 5.15). */
@Composable
fun WinRatesScreen(onBack: () -> Unit) {
    val vm = appViewModel { c, _ -> WinRatesViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val tag by vm.tag.collectAsStateWithLifecycle()

    Scaffold(topBar = { BackTopBar("Win rates", onBack) }) { padding ->
        val state = ui ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "From your results log: how often each variant was marked the winner, and how its takes were rated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.tags.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tag == null, onClick = { vm.setTag(null) }, label = { Text("All genres") })
                    state.tags.forEach { t -> FilterChip(selected = tag == t, onClick = { vm.setTag(if (tag == t) null else t) }, label = { Text(t) }) }
                }
            }
            if (state.stats.isEmpty()) {
                Text("Nothing sent to Suno yet${tag?.let { " for $it" } ?: ""}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            if (state.decidedSongs == 0) {
                Text(
                    "No winners marked yet. On a song's results, tap “<variant> won” once you've heard the takes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.stats.forEach { StatsCard(it) }
        }
    }
}

@Composable
private fun StatsCard(s: VariantStats) {
    val rate = s.winRate
    val winText = if (rate == null) "No decided songs yet" else "Won ${s.wins} of ${s.decided} song${if (s.decided == 1) "" else "s"} (${(rate * 100).roundToInt()}%)"
    val ratingText = "${s.takes} take${if (s.takes == 1) "" else "s"}" + (s.averageRating?.let { " · average %.1f★".format(it) } ?: " · not rated")
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp).clearAndSetSemantics { contentDescription = "${s.name}: $winText. $ratingText" },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(s.name, style = MaterialTheme.typography.titleMedium)
            Text(winText, style = MaterialTheme.typography.bodyMedium)
            if (rate != null) LinearProgressIndicator(progress = { rate.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(ratingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
