package app.sunosongwriter.ui.song

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sunosongwriter.domain.model.StyleSection
import app.sunosongwriter.domain.model.SunoSettings
import app.sunosongwriter.domain.model.Version
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.library.NameDialog
import app.sunosongwriter.ui.results.stars
import kotlin.math.roundToInt

private enum class SongTab(val label: String) { STYLE("Style"), LYRICS("Lyrics"), SETTINGS("Settings"), TEAM("Team"), HISTORY("History") }

private sealed interface SongDialog {
    data object NewVariant : SongDialog
    data object RenameVariant : SongDialog
    data object DeleteVariant : SongDialog
    data object SaveNote : SongDialog
    data object DiscardEdits : SongDialog
    data object QuickStart : SongDialog
}

@Composable
fun SongScreen(
    onBack: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onCompare: (songId: Long, fromId: Long, toId: Long) -> Unit,
    onSideBySide: (songId: Long, fromId: Long, toId: Long) -> Unit,
    onOpenParticipants: () -> Unit,
    onStartRoom: (songId: Long, variantId: Long) -> Unit,
    onOpenRoom: (Long) -> Unit,
    onHandoff: (songId: Long, versionId: Long) -> Unit,
    onOpenResults: (songId: Long, snapshotId: Long) -> Unit,
) {
    val vm = appViewModel { c, saved -> SongViewModel(saved, c.songs, c.snapshots, c.results, c.recipes) }
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()
    val team = appViewModel { c, saved -> TeamViewModel(saved, c) }
    val teamUi by team.ui.collectAsStateWithLifecycle()
    val teamMessage by team.message.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val edit by vm.edit.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(if (vm.openTeam) SongTab.TEAM.ordinal else 0) }
    var dialog by remember { mutableStateOf<SongDialog?>(null) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    LaunchedEffect(teamMessage) {
        teamMessage?.let { snackbar.showSnackbar(it); team.clearMessage() }
    }
    LaunchedEffect(ui?.variant?.id) { ui?.variant?.id?.let(team::setVariant) }
    val editing = edit != null
    val leave = { if (vm.hasUnsavedChanges) dialog = SongDialog.DiscardEdits else { vm.cancelEdit(); onBack() } }
    BackHandler(enabled = editing, onBack = leave)

    Scaffold(
        topBar = {
            BackTopBar(ui?.song?.title?.ifBlank { "Untitled" } ?: "", if (editing) leave else onBack) {
                if (editing) {
                    TextButton(onClick = { if (vm.hasUnsavedChanges) dialog = SongDialog.DiscardEdits else vm.cancelEdit() }) { Text("Cancel") }
                    TextButton(onClick = { dialog = SongDialog.SaveNote }) { Text("Save") }
                } else {
                    IconButton(onClick = { ui?.let { onHandoff(vm.songId, it.current.id) } }) { Icon(Icons.AutoMirrored.Filled.Send, "Send to Suno") }
                    IconButton(onClick = vm::startEdit) { Icon(Icons.Filled.Edit, "Edit style, lyrics and settings") }
                    IconButton(onClick = { onOpenDetails(vm.songId) }) { Icon(Icons.Filled.Info, "Song details") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Quick start from the idea") }, onClick = { menu = false; dialog = SongDialog.QuickStart })
                            DropdownMenuItem(text = { Text("New variant") }, onClick = { menu = false; dialog = SongDialog.NewVariant })
                            DropdownMenuItem(text = { Text("Rename variant") }, onClick = { menu = false; dialog = SongDialog.RenameVariant })
                            DropdownMenuItem(
                                text = { Text("Variants side by side") },
                                enabled = (ui?.variants?.size ?: 0) > 1,
                                onClick = {
                                    menu = false
                                    vm.sideBySide { a, b -> onSideBySide(vm.songId, a, b) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete variant") },
                                enabled = (ui?.variants?.size ?: 0) > 1,
                                onClick = { menu = false; dialog = SongDialog.DeleteVariant },
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val state = ui ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.variants.forEach { v ->
                    FilterChip(selected = v.id == state.variant.id, onClick = { vm.selectVariant(v.id) }, enabled = !editing || v.id == state.variant.id, label = { Text(v.name) })
                }
                if (!editing) IconButton(onClick = { dialog = SongDialog.NewVariant }) { Icon(Icons.Filled.Add, "New variant") }
            }
            Text(
                if (editing) "Editing ${state.variant.name}. Saving creates v${state.current.number + 1}." else versionLine(state.variant.name, state.current),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                SongTab.entries.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t.label) }, enabled = !(editing && (t == SongTab.HISTORY || t == SongTab.TEAM)))
                }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val e = edit
                when (SongTab.entries[tab]) {
                    SongTab.STYLE -> if (e != null) StyleEditor(e, vm) else StyleView(state.current, onEdit = vm::startEdit, onQuickStart = { dialog = SongDialog.QuickStart })
                    SongTab.LYRICS -> if (e != null) LyricsEditor(e, vm) else LyricsView(state.current, onEdit = vm::startEdit, onQuickStart = { dialog = SongDialog.QuickStart })
                    SongTab.SETTINGS -> if (e != null) SettingsEditor(e.content.settings) { s -> vm.updateEdit { it.copy(content = it.content.copy(settings = s)) } } else SettingsView(state.current.content.settings)
                    SongTab.TEAM -> teamUi?.let { t ->
                        TeamTab(
                            t, team, state.history,
                            onCompare = { from, to -> onCompare(vm.songId, from, to) },
                            onOpenParticipants = onOpenParticipants,
                            onStartRoom = { onStartRoom(vm.songId, state.variant.id) },
                            onOpenRoom = onOpenRoom,
                        )
                    }
                    SongTab.HISTORY -> {
                        SentList(snapshots, onOpen = { onOpenResults(vm.songId, it) })
                        History(state, onCompare = { v -> onCompare(vm.songId, v.parentVersionId ?: 0, v.id) })
                    }
                }
            }
        }
    }

    when (dialog) {
        null -> Unit
        SongDialog.NewVariant -> NewVariantDialog(
            sourceLabel = ui?.let { "${it.variant.name} v${it.current.number}" } ?: "",
            onDismiss = { dialog = null },
            onCreate = { name, copy -> vm.createVariant(name, copy); dialog = null },
        )
        SongDialog.RenameVariant -> NameDialog("Rename variant", ui?.variant?.name.orEmpty(), "Rename", onDismiss = { dialog = null }) { vm.renameVariant(it); dialog = null }
        SongDialog.DeleteVariant -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${ui?.variant?.name}?") },
            text = { Text("All of its versions are deleted. Other variants are kept.") },
            confirmButton = { TextButton(onClick = { vm.deleteVariant(); dialog = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        SongDialog.SaveNote -> SaveDialog(onDismiss = { dialog = null }, onSave = { note -> vm.save(note); dialog = null })
        SongDialog.DiscardEdits -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Discard changes?") },
            text = { Text("Your edits haven't been saved as a version.") },
            confirmButton = { TextButton(onClick = { vm.cancelEdit(); dialog = null }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Keep editing") } },
        )
        SongDialog.QuickStart -> QuickStartDialog(
            members = teamUi?.members.orEmpty(),
            initialIdea = ui?.song?.concept.orEmpty(),
            newSong = false,
            replacesContent = ui?.current?.content?.let { !it.style.isEmpty || it.lyrics.isNotBlank() } == true,
            onDismiss = { dialog = null },
            onOpenParticipants = { dialog = null; onOpenParticipants() },
        ) { request ->
            dialog = null
            // The step streams on the Team tab.
            team.quickStart(request) { tab = SongTab.TEAM.ordinal }
        }
    }
}

private fun versionLine(variant: String, v: Version): String {
    val ago = DateUtils.getRelativeTimeSpanString(v.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    return "$variant v${v.number} · ${v.author.label} · $ago"
}

// Style

@Composable
private fun StyleView(version: Version, onEdit: () -> Unit, onQuickStart: () -> Unit) {
    val style = version.content.style
    if (style.isEmpty) {
        EmptyTab("No style prompt yet.", onEdit, onQuickStart)
        return
    }
    if (style.freeText != null) {
        Text("Edited as text", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(style.freeText) }
        return
    }
    StyleSection.renderOrder.filter { style[it].isNotBlank() }.forEach { section ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(section.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SelectionContainer { Text(style[section]) }
        }
    }
}

@Composable
private fun StyleEditor(edit: SongEdit, vm: SongViewModel) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected = !edit.styleAsText, onClick = { vm.setStyleAsText(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Sections") }
        SegmentedButton(selected = edit.styleAsText, onClick = { vm.setStyleAsText(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("As text") }
    }
    if (edit.styleAsText) {
        OutlinedTextField(
            value = edit.styleText,
            onValueChange = { t -> vm.updateEdit { it.copy(styleText = t) } },
            label = { Text("Style prompt") },
            supportingText = { Text("Labels like \"Vocals:\" are recognised for diffs. ${edit.styleText.length} characters.") },
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Text(
        "In the order the Suno v6 guide recommends. Describe what each part is doing, not just what it is.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    (StyleSection.entries - StyleSection.NOTES + StyleSection.NOTES).forEach { section ->
        OutlinedTextField(
            value = edit.content.style[section],
            onValueChange = { t -> vm.updateEdit { it.copy(content = it.content.copy(style = it.content.style.with(section, t))) } },
            label = { Text(section.label) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text("${edit.content.style.flatText().length} characters", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// Lyrics

@Composable
private fun LyricsView(version: Version, onEdit: () -> Unit, onQuickStart: () -> Unit) {
    val lyrics = version.content.lyrics
    if (lyrics.isBlank()) {
        EmptyTab("No lyrics yet.", onEdit, onQuickStart)
        return
    }
    SelectionContainer { Text(lyrics, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun LyricsEditor(edit: SongEdit, vm: SongViewModel) {
    OutlinedTextField(
        value = edit.content.lyrics,
        onValueChange = { t -> vm.updateEdit { it.copy(content = it.content.copy(lyrics = t)) } },
        label = { Text("Lyrics") },
        supportingText = { Text("[Section] tags in brackets, backing vocals in (parentheses). ${edit.content.lyrics.length} characters.") },
        minLines = 16,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Default),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
}

// Settings

@Composable
private fun SettingsView(settings: SunoSettings) {
    Text("Set these by hand in Suno; they can't be pasted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    settings.describe().forEach { (label, value) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(value ?: "Not set", color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsEditor(settings: SunoSettings, onChange: (SunoSettings) -> Unit) {
    OutlinedTextField(
        value = settings.model.orEmpty(),
        onValueChange = { onChange(settings.copy(model = it.ifBlank { null })) },
        label = { Text("Suno model") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SunoSettings.MODEL_SUGGESTIONS.forEach { m -> SuggestionChip(onClick = { onChange(settings.copy(model = m)) }, label = { Text(m) }) }
    }
    PercentSetting("Weirdness", settings.weirdness, default = 35, hint = "30–50% is the guide's creative sweet spot") { onChange(settings.copy(weirdness = it)) }
    PercentSetting("Style Influence", settings.styleInfluence, default = 90, hint = "The guide suggests 80–95% while developing a song") { onChange(settings.copy(styleInfluence = it)) }
    PercentSetting("Variety", settings.variety, default = 0, hint = "Start at 0; raise it later for stranger results", suffix = "") { onChange(settings.copy(variety = it)) }
}

@Composable
private fun PercentSetting(label: String, value: Int?, default: Int, hint: String, suffix: String = "%", onChange: (Int?) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = value != null, onCheckedChange = { onChange(if (it) default else null) })
            Text(label, modifier = Modifier.weight(1f).clickable { onChange(if (value == null) default else null) })
            Text(value?.let { "$it$suffix" } ?: "Not set", modifier = Modifier.width(72.dp))
        }
        if (value != null) {
            Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) }, valueRange = 0f..100f)
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// History

@Composable
private fun History(state: SongUi, onCompare: (Version) -> Unit) {
    Text(
        "${state.variant.name} has ${state.history.size} version${if (state.history.size == 1) "" else "s"}. Tap one to see what changed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.history.forEach { v ->
        ListItem(
            headlineContent = { Text("v${v.number} · ${v.changeNote.ifBlank { "No note" }}") },
            supportingContent = {
                Text("${v.author.label} · ${DateUtils.getRelativeTimeSpanString(v.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}")
            },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            modifier = Modifier.clickable { onCompare(v) },
        )
        HorizontalDivider()
    }
}

/** Snapshots of what was sent to Suno (spec 5.11), newest first, with how they went (5.12). Tap for the results log. */
@Composable
private fun SentList(snapshots: List<SentSummary>, onOpen: (Long) -> Unit) {
    if (snapshots.isEmpty()) return
    Text("Sent to Suno", style = MaterialTheme.typography.titleMedium)
    snapshots.forEach { summary ->
        val s = summary.snapshot
        val sent = "Sent ${DateUtils.getRelativeTimeSpanString(s.sentAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}"
        val results = when (summary.takes) {
            0 -> "no results logged"
            1 -> "1 take"
            else -> "${summary.takes} takes"
        } + (summary.bestRating?.let { " · best ${stars(it)}" } ?: "")
        ListItem(
            headlineContent = { Text(s.versionLabel + if (summary.isWinner) " · ★ winner" else "") },
            supportingContent = {
                Column {
                    Text("$sent · $results")
                    summary.recipeName?.let { Text("Recipe: $it", color = MaterialTheme.colorScheme.primary) }
                }
            },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            modifier = Modifier.clickable { onOpen(s.id) },
        )
        HorizontalDivider()
    }
    Text("Versions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun EmptyTab(text: String, onEdit: () -> Unit, onQuickStart: () -> Unit) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Quick start", style = MaterialTheme.typography.titleSmall)
            Text("Have a model turn your idea into a first draft, then refine it with your team.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onQuickStart) { Text("Quick start") }
        }
    }
    OutlinedButton(onClick = onEdit) { Text("Write it yourself") }
}

@Composable
private fun NewVariantDialog(sourceLabel: String, onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var copy by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New variant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, placeholder = { Text("e.g. Consensus") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { copy = !copy }) {
                    Checkbox(checked = copy, onCheckedChange = { copy = it })
                    Text("Start from a copy of $sourceLabel")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim(), copy) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SaveDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save new version") },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("What changed? (optional)") },
                placeholder = { Text("e.g. tightened the chorus") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(note.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
