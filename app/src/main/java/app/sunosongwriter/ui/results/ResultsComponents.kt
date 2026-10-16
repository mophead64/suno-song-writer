package app.sunosongwriter.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sunosongwriter.domain.results.IssueFix
import app.sunosongwriter.domain.results.SunoLinks
import app.sunosongwriter.domain.results.Take
import app.sunosongwriter.ui.components.RelayLauncher

/** "★★★★☆", or "Not rated". */
fun stars(rating: Int?): String = rating?.let { "★".repeat(it) + "☆".repeat(5 - it) } ?: "Not rated"

/** Five tappable stars; tapping the current rating clears it. */
@Composable
fun StarRating(rating: Int?, onChange: (Int?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { n ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .selectable(selected = rating == n, role = Role.RadioButton, onClick = { onChange(if (rating == n) null else n) })
                    .semantics { contentDescription = "$n star${if (n > 1) "s" else ""}" },
            ) {
                Text(
                    if (rating != null && n <= rating) "★" else "☆",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (rating != null && n <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** Log or correct one take: link, rating, issue tags from the guide's list (or custom), notes (spec 5.12). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TakeDialog(
    take: Take?,
    catalog: List<IssueFix>,
    onDismiss: () -> Unit,
    onAddCustomTag: (String, (String?) -> Unit) -> Unit,
    onSave: (link: String?, rating: Int?, notes: String, issues: List<String>) -> Unit,
) {
    val context = LocalContext.current
    var link by remember { mutableStateOf(take?.link.orEmpty()) }
    var rating by remember { mutableStateOf(take?.rating) }
    var notes by remember { mutableStateOf(take?.notes.orEmpty()) }
    var issues by remember { mutableStateOf(take?.issues.orEmpty().toSet()) }
    var customName by remember { mutableStateOf<String?>(null) }
    var customError by remember { mutableStateOf(false) }
    // Tags on the take whose custom entry was deleted still show, so they can be removed.
    val shown = catalog + issues.filter { t -> catalog.none { it.tag == t } }.map { IssueFix(it, it, "", isBuiltIn = false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (take == null) "Log a take" else "Take ${take.index}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Suno link") },
                    placeholder = { Text("https://suno.com/s/…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { RelayLauncher.clipboardText(context)?.let { SunoLinks.extract(it) }?.let { link = it } }) { Text("Paste link") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rating", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (rating != null) TextButton(onClick = { rating = null }) { Text("Clear") }
                }
                StarRating(rating) { rating = it }
                Text("What went wrong?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    shown.forEach { fix ->
                        FilterChip(
                            selected = fix.tag in issues,
                            onClick = { issues = if (fix.tag in issues) issues - fix.tag else issues + fix.tag },
                            label = { Text(fix.label) },
                        )
                    }
                    AssistChip(onClick = { customName = ""; customError = false }, label = { Text("Custom tag") }, leadingIcon = { Icon(Icons.Filled.Add, null) })
                }
                customName?.let { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { customName = it; customError = false },
                            label = { Text("New tag") },
                            isError = customError,
                            supportingText = if (customError) ({ Text("Empty, or that tag already exists") }) else null,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            onAddCustomTag(name) { tag ->
                                if (tag == null) customError = true else { issues = issues + tag; customName = null }
                            }
                        }) { Text("Add") }
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    placeholder = { Text("e.g. last chorus cut off mid-line at 3:12") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(SunoLinks.extract(link), rating, notes, issues.toList()) },
                enabled = link.isNotBlank() || rating != null || notes.isNotBlank() || issues.isNotEmpty(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
