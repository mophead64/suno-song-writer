package app.sunosongwriter.ui.song

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.sunosongwriter.domain.model.SongProgress
import app.sunosongwriter.domain.model.SongStep

/**
 * Where the song is up to, from idea to Suno results, and the one thing to do next (testing feedback:
 * "tell the user they need to set up participants first"). Hidden once every step is done.
 */
@Composable
fun SongTimeline(progress: SongProgress, actionLabel: (SongStep) -> String, onAction: (SongStep) -> Unit, modifier: Modifier = Modifier) {
    val next = progress.next ?: return
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).clearAndSetSemantics {
                    contentDescription = "Progress: " + SongStep.entries.joinToString { "${it.label} ${if (it in progress.done) "done" else "to do"}" }
                },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongStep.entries.forEachIndexed { i, step ->
                    if (i > 0) Text("›", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelMedium)
                    val done = step in progress.done
                    Text(
                        (if (done) "✓ " else "") + step.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            step == next -> MaterialTheme.colorScheme.primary
                            done -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Next: ${next.next}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { onAction(next) }) { Text(actionLabel(next)) }
            }
        }
    }
}
