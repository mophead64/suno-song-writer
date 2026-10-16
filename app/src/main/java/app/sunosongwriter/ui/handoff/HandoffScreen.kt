package app.sunosongwriter.ui.handoff

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sunosongwriter.AppContainer
import app.sunosongwriter.domain.lint.LintIssue
import app.sunosongwriter.domain.lint.LintLevel
import app.sunosongwriter.domain.lint.SunoLimits
import app.sunosongwriter.domain.lint.SunoLint
import app.sunosongwriter.domain.model.Snapshot
import app.sunosongwriter.domain.model.SunoSettings
import app.sunosongwriter.domain.model.Version
import app.sunosongwriter.ui.HandoffRoute
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.RelayLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

data class HandoffUi(
    val songTitle: String,
    /** "Consensus v5". */
    val label: String,
    val style: String,
    val lyrics: String,
    val settings: SunoSettings,
    val issues: List<LintIssue>,
    val limits: SunoLimits,
    /** Set when showing a frozen snapshot rather than a live version. */
    val snapshot: Snapshot?,
    /** Earlier snapshots of this version, newest first. */
    val sentBefore: List<Snapshot>,
    val version: Version?,
)

/** The handoff to Suno (spec 5.9): clean text to copy, lint, the slider checklist, and snapshots (5.11). */
class HandoffViewModel(savedState: SavedStateHandle, private val c: AppContainer) : ViewModel() {
    private val route = savedState.toRoute<HandoffRoute>()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    val ui = combine(
        c.songs.observeSong(route.songId),
        c.songs.observeVersions(route.songId),
        c.songs.observeVariants(route.songId),
        c.snapshots.observeForSong(route.songId),
        c.settings.sunoLimits,
    ) { song, versions, variants, snapshots, limits ->
        val snapshot = snapshots.firstOrNull { it.id == route.snapshotId }
        val version = versions.firstOrNull { it.id == (snapshot?.versionId ?: route.versionId) }
        val label = snapshot?.versionLabel ?: version?.let { v -> "${variants.firstOrNull { it.id == v.variantId }?.name ?: "?"} v${v.number}" } ?: ""
        HandoffUi(
            songTitle = song?.title?.ifBlank { "Untitled" } ?: "",
            label = label,
            style = snapshot?.styleText ?: version?.let { SunoLint.cleanStyle(it.content.style) }.orEmpty(),
            lyrics = snapshot?.lyricsText ?: version?.let { SunoLint.cleanLyrics(it.content.lyrics) }.orEmpty(),
            settings = snapshot?.settings ?: version?.content?.settings ?: SunoSettings(),
            issues = if (snapshot == null) version?.let { SunoLint.check(it.content, limits) }.orEmpty() else emptyList(),
            limits = limits,
            snapshot = snapshot,
            sentBefore = if (snapshot == null) snapshots.filter { it.versionId == version?.id } else emptyList(),
            version = version,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun markSent() {
        val state = ui.value ?: return
        val version = state.version ?: return
        viewModelScope.launch {
            c.snapshots.markSent(route.songId, version, state.label)
            _message.value = "Marked as sent. The exact text is saved as a snapshot."
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

@Composable
fun HandoffScreen(onBack: () -> Unit, onOpenResults: (songId: Long, snapshotId: Long) -> Unit) {
    val vm = appViewModel { c, saved -> HandoffViewModel(saved, c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ticked = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    fun copy(label: String, text: String) {
        RelayLauncher.copy(context, label, text)
        val state = ui ?: return
        // Replace any earlier "copied" message rather than queueing behind it.
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            val offerMark = state.snapshot == null && state.sentBefore.isEmpty()
            val result = snackbar.showSnackbar("$label copied", actionLabel = if (offerMark) "Mark as sent" else null, withDismissAction = !offerMark)
            if (result == SnackbarResult.ActionPerformed) vm.markSent()
        }
    }

    Scaffold(topBar = { BackTopBar(if (ui?.snapshot != null) "Sent snapshot" else "Send to Suno", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val state = ui ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${state.songTitle} · ${state.label}", style = MaterialTheme.typography.titleMedium)
            state.snapshot?.let {
                Text("Sent ${formatTime(it.sentAt)}. This is exactly what was copied; it never changes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.snapshot == null) LintCard(state.issues)

            CopyCard("Style", state.style, state.limits.styleChars, onCopy = { copy("Style", state.style) })
            CopyCard("Lyrics", state.lyrics, state.limits.lyricsChars, onCopy = { copy("Lyrics", state.lyrics) })

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Set these in Suno", style = MaterialTheme.typography.titleSmall)
                    Text("Sliders can't be pasted. Tick them off as you set them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.settings.describe().forEach { (label, value) ->
                        if (value == null) {
                            Text("$label: leave at Suno's default", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { ticked[label] = ticked[label] != true }) {
                                Checkbox(checked = ticked[label] == true, onCheckedChange = { ticked[label] = it })
                                Text("$label: $value")
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val installed = remember { context.packageManager.getLaunchIntentForPackage(SUNO_PACKAGE) != null }
                OutlinedButton(onClick = { openSuno(context) }) { Text(if (installed) "Open Suno" else "Get Suno") }
                state.snapshot?.let { s -> Button(onClick = { onOpenResults(s.songId, s.id) }) { Text("Log results") } }
                if (state.snapshot == null) {
                    if (state.sentBefore.isEmpty()) {
                        Button(onClick = vm::markSent) { Text("Mark as sent") }
                    } else {
                        FilledTonalButton(onClick = vm::markSent) { Text("Mark sent again") }
                    }
                }
            }
            if (state.sentBefore.isNotEmpty()) {
                Text(
                    "Sent ${state.sentBefore.size} time${if (state.sentBefore.size > 1) "s" else ""}, last ${formatTime(state.sentBefore.first().sentAt)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Lint results (spec 5.10). Warnings only: copying is never blocked. */
@Composable
private fun LintCard(issues: List<LintIssue>) {
    val warnings = issues.count { it.level == LintLevel.WARNING }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (warnings > 0) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (issues.isEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null)
                    Text("Follows the Suno v6 guide's checks.", style = MaterialTheme.typography.bodyMedium)
                }
                return@Column
            }
            Text(
                if (warnings > 0) "$warnings thing${if (warnings > 1) "s" else ""} to check before you paste" else "Notes",
                style = MaterialTheme.typography.titleSmall,
            )
            issues.forEach { issue ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (issue.level == LintLevel.WARNING) Icons.Filled.Warning else Icons.Filled.Info,
                        contentDescription = if (issue.level == LintLevel.WARNING) "Warning" else "Note",
                    )
                    Text(issue.message, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("You can still copy; these are only warnings.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CopyCard(label: String, text: String, limit: Int, onCopy: () -> Unit) {
    val over = text.length > limit
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "%,d / %,d".format(text.length, limit),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = "${text.length} of $limit characters" + if (over) ", over the limit" else "" },
                )
            }
            if (text.isBlank()) {
                Text("Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Box(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Button(onClick = onCopy, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Copy ${label.lowercase()}") }
        }
    }
}

private const val SUNO_PACKAGE = "com.suno.android"

private fun openSuno(context: android.content.Context) {
    val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(SUNO_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(launch)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$SUNO_PACKAGE".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$SUNO_PACKAGE".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private fun formatTime(millis: Long): String =
    if (System.currentTimeMillis() - millis < DateUtils.DAY_IN_MILLIS) "at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
    else "on " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
