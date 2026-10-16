package app.sunosongwriter.ui.backends

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sunosongwriter.App
import app.sunosongwriter.data.presets.BackendPreset
import app.sunosongwriter.domain.model.BackendKind
import app.sunosongwriter.domain.model.FreeAccess
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.Tag
import app.sunosongwriter.ui.components.TagRow

@Composable
fun PresetPickerScreen(onBack: () -> Unit, onPick: (String) -> Unit) {
    val presets = (LocalContext.current.applicationContext as App).container.presets.presets

    Scaffold(topBar = { BackTopBar("Add a backend", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Grouped by how you use them. Limits and free tiers change often, so each one shows when it was last checked.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            val groups = listOf(
                "On your computer" to presets.filter { it.local },
                "Free chat apps (you relay by hand)" to presets.filter { it.kind == BackendKind.RELAY },
                "API providers" to presets.filter { !it.local && it.kind != BackendKind.RELAY && it.id != "custom" },
                "Other" to presets.filter { it.id == "custom" },
            )
            groups.filter { it.second.isNotEmpty() }.forEach { (heading, group) ->
                item(key = "h-$heading") {
                    Text(heading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                }
                items(group, key = { it.id }) { preset ->
                    ListItem(
                        headlineContent = { Text(preset.name) },
                        supportingContent = {
                            Column {
                                Text(preset.description)
                                TagRow {
                                    Tag(costSummary(preset))
                                    if (preset.unverified) Tag("Free tier unverified")
                                    Tag("Checked ${preset.verifiedOn}")
                                }
                            }
                        },
                        modifier = Modifier.clickable { onPick(preset.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun costSummary(preset: BackendPreset): String = if (preset.kind == BackendKind.RELAY) "Free plan, relayed by you" else when (preset.freeAccess) {
    FreeAccess.ALL -> "Free, local"
    FreeAccess.PRICING -> "Free models available"
    FreeAccess.KEY_TIER -> if (preset.freeTierDefault) "Free tier" else "Paid unless your plan is free"
}
