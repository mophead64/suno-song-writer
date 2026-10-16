package app.sunosongwriter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sunosongwriter.domain.usage.RequestsLeft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        // One line, so long song titles don't push the actions around at large font sizes.
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = actions,
    )
}

/** Free / Paid label shown on every model and participant (spec 5.1). */
@Composable
fun CostLabel(isFree: Boolean, modifier: Modifier = Modifier) {
    Tag(
        text = if (isFree) "Free" else "Paid",
        container = if (isFree) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        content = if (isFree) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier,
    )
}

@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceVariant,
    content: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
fun TagRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** "12 requests left today", marked as an estimate when it comes from the app's own count (spec 5.2). */
fun RequestsLeft.describe(): String {
    val prefix = if (estimated) "~" else ""
    val of = limit?.let { " of $it" } ?: ""
    return "$prefix$remaining$of requests left today"
}

@Composable
fun RequestsLeftText(left: RequestsLeft?, modifier: Modifier = Modifier) {
    if (left == null) return
    val color = if (left.remaining == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val description = left.describe() + if (left.estimated) " (estimated from this app's count)" else ""
    Text(
        left.describe(),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier.semantics { contentDescription = description },
    )
}
