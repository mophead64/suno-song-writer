package app.sunosongwriter.ui.participants

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.AppContainer
import app.sunosongwriter.domain.model.Participant
import app.sunosongwriter.domain.usage.RequestsLeft
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.CostLabel
import app.sunosongwriter.ui.components.RequestsLeftText
import app.sunosongwriter.ui.components.Tag
import app.sunosongwriter.ui.components.TagRow
import app.sunosongwriter.ui.components.buildModelOptions
import app.sunosongwriter.ui.components.describeRef
import app.sunosongwriter.ui.components.requestsLeft
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ParticipantRow(
    val participant: Participant,
    val modelLabel: String,
    val isFree: Boolean?,
    val unavailable: Boolean,
    val requestsLeft: RequestsLeft?,
)

class ParticipantListViewModel(c: AppContainer) : ViewModel() {
    val rows = combine(
        c.participants.participants,
        c.backends.backends,
        c.backends.allModels,
        c.requestCounts.observeRecent(),
        c.quotas.changes,
    ) { participants, backends, models, counts, _ ->
        val options = buildModelOptions(backends, models)
        participants.map { p ->
            val option = options.firstOrNull { it.ref == p.model }
            ParticipantRow(
                participant = p,
                modelLabel = describeRef(p.model, options, backends),
                isFree = option?.isFree,
                unavailable = p.model != null && option?.model?.available != true,
                requestsLeft = p.model?.let { requestsLeft(it, backends, counts, c.quotas, c.clock(), option?.model?.pricedFree) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun ParticipantListScreen(onBack: () -> Unit, onAdd: () -> Unit, onOpen: (Long) -> Unit, onStarterTeam: () -> Unit) {
    val vm = appViewModel { c, _ -> ParticipantListViewModel(c) }
    val rows by vm.rows.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { BackTopBar("Participants", onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                // This Material 3 version doesn't expose the FAB's text to accessibility services.
                modifier = Modifier.semantics { contentDescription = "Add participant" },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add participant") },
            )
        },
    ) { padding ->
        val list = rows ?: return@Scaffold
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No participants yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A participant is a model with a name and a role, like \"Gemini, lyricist\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    androidx.compose.material3.Button(onClick = onStarterTeam, modifier = Modifier.padding(top = 16.dp)) { Text("Set up a free starter team") }
                }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(list, key = { it.participant.id }) { row ->
                val p = row.participant
                ListItem(
                    leadingContent = { Avatar(p.avatar) },
                    headlineContent = { Text(p.name) },
                    supportingContent = {
                        Column {
                            Text(row.modelLabel, style = MaterialTheme.typography.bodySmall)
                            RequestsLeftText(row.requestsLeft)
                        }
                    },
                    trailingContent = {
                        TagRow {
                            p.defaultRole?.let { Tag(it.label) }
                            if (row.unavailable) Tag("Unavailable", container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
                            row.isFree?.let { CostLabel(it) }
                        }
                    },
                    modifier = Modifier.clickable { onOpen(p.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun Avatar(text: String, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text.ifBlank { "?" }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
