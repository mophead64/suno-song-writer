package app.sunosongwriter.ui.recipes

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sunosongwriter.AppContainer
import app.sunosongwriter.domain.model.SunoSettings
import app.sunosongwriter.domain.results.Recipe
import app.sunosongwriter.domain.results.RecipeFilter
import app.sunosongwriter.domain.results.RecipeNames
import app.sunosongwriter.ui.RecipeRoute
import app.sunosongwriter.ui.appViewModel
import app.sunosongwriter.ui.components.BackTopBar
import app.sunosongwriter.ui.components.RelayLauncher
import app.sunosongwriter.ui.components.Tag
import app.sunosongwriter.ui.components.TagRow
import app.sunosongwriter.ui.results.stars
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Library

data class RecipeListUi(val recipes: List<Recipe>, val total: Int, val tags: List<String>, val artists: List<String>, val names: Map<Long, String>)

class RecipeListViewModel(c: AppContainer) : ViewModel() {
    private val _filter = MutableStateFlow(RecipeFilter())
    val filter = _filter.asStateFlow()

    val ui = combine(c.recipes.recipes, _filter) { recipes, filter ->
        RecipeListUi(
            recipes = recipes.filter(filter::matches),
            total = recipes.size,
            tags = recipes.flatMap { it.tags }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() },
            artists = recipes.map { it.artist }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() },
            names = recipes.associate { it.id to it.name },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun updateFilter(change: (RecipeFilter) -> RecipeFilter) = _filter.update(change)
}

/** The recipe library (spec 5.14), filtered by genre tag, artist and rating. */
@Composable
fun RecipeListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val vm = appViewModel { c, _ -> RecipeListViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()

    Scaffold(topBar = { BackTopBar("Recipes", onBack) }) { padding ->
        val state = ui ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.total == 0) {
                Text(
                    "No recipes yet. When a song comes out well, open its results from the song's History tab and save it as a recipe.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipMenu("Genre", filter.tag, state.tags.map { it to it }) { t -> vm.updateFilter { it.copy(tag = t) } }
                ChipMenu("Artist", filter.artist, state.artists.map { it to it }) { a -> vm.updateFilter { it.copy(artist = a) } }
                ChipMenu("Rating", filter.minRating?.let { "$it★ and up" }, (5 downTo 1).map { "$it★ and up" to it }) { r -> vm.updateFilter { it.copy(minRating = r) } }
            }
            if (state.recipes.isEmpty()) {
                Text("No recipes match.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            LazyColumn {
                items(state.recipes, key = { it.id }) { r ->
                    RecipeRow(r, state.names[r.parentRecipeId], onClick = { onOpen(r.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RecipeRow(r: Recipe, parentName: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(r.name) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listOf(r.songTitle.ifBlank { "Untitled" }, r.artist).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                if (r.tags.isNotEmpty()) TagRow { r.tags.take(4).forEach { Tag(it) } }
                parentName?.let { Text("Adapted from $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(if (r.rating == null) "Not rated" else stars(r.rating), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                r.settings.model?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** A filter chip that opens a menu; "Any" clears it. */
@Composable
private fun <T> ChipMenu(label: String, selected: String?, options: List<Pair<String, T>>, onPick: (T?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text(selected ?: label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Any ${label.lowercase()}") }, onClick = { open = false; onPick(null) })
            options.forEach { (text, value) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) }) }
        }
    }
}

// Detail

data class RecipeUi(val recipe: Recipe, val parent: Recipe?, val adaptations: List<Recipe>, val songExists: Boolean)

class RecipeViewModel(savedState: SavedStateHandle, private val c: AppContainer) : ViewModel() {
    private val id = savedState.toRoute<RecipeRoute>().recipeId
    private val _created = MutableStateFlow<Long?>(null)

    /** A song or recipe just created, to navigate to: (song id, recipe id). */
    val created = _created.asStateFlow()
    private val _adapted = MutableStateFlow<Long?>(null)
    val adapted = _adapted.asStateFlow()

    val ui = combine(c.recipes.recipes, c.songs.songs.map { list -> list.map { it.id }.toSet() }) { recipes, songIds ->
        val recipe = recipes.firstOrNull { it.id == id } ?: return@combine null
        RecipeUi(
            recipe = recipe,
            parent = recipes.firstOrNull { it.id == recipe.parentRecipeId },
            adaptations = recipes.filter { it.parentRecipeId == id }.sortedBy { it.createdAt },
            songExists = recipe.songId in songIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun newSong(title: String, artist: String, withStructure: Boolean) {
        val recipe = ui.value?.recipe ?: return
        viewModelScope.launch { _created.value = c.recipes.newSong(recipe, title, artist, withStructure) }
    }

    fun adapt(name: String, styleText: String, settings: SunoSettings) {
        val recipe = ui.value?.recipe ?: return
        viewModelScope.launch { _adapted.value = c.recipes.adapt(recipe, name, styleText, settings) }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        c.recipes.delete(id)
        onDone()
    }

    fun consumeNavigation() {
        _created.value = null
        _adapted.value = null
    }
}

private sealed interface RecipeDialog {
    data object NewSong : RecipeDialog
    data object Adapt : RecipeDialog
    data object Delete : RecipeDialog
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipeScreen(onBack: () -> Unit, onOpenSong: (Long) -> Unit, onOpenRecipe: (Long) -> Unit, onNewSong: (Long) -> Unit) {
    val vm = appViewModel { c, saved -> RecipeViewModel(saved, c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val created by vm.created.collectAsStateWithLifecycle()
    val adapted by vm.adapted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<RecipeDialog?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    LaunchedEffect(created, adapted) {
        created?.let { vm.consumeNavigation(); onNewSong(it) }
        adapted?.let { vm.consumeNavigation(); onOpenRecipe(it) }
    }

    Scaffold(
        topBar = {
            BackTopBar(ui?.recipe?.name.orEmpty(), onBack) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Delete recipe") }, onClick = { menu = false; dialog = RecipeDialog.Delete })
                    }
                }
            }
        },
    ) { padding ->
        val state = ui ?: return@Scaffold
        val r = state.recipe
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                listOf(r.songTitle.ifBlank { "Untitled" }, r.artist).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (r.rating == null) "Not rated yet" else stars(r.rating), color = MaterialTheme.colorScheme.primary)
                if (r.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { r.tags.forEach { Tag(it) } }
            }
            Text("Saved exactly as it was sent. Recipes never change; adapting one saves a new recipe.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.parent?.let { p -> TextButton(onClick = { onOpenRecipe(p.id) }) { Text("Adapted from ${p.name}") } }
            if (state.songExists) r.songId?.let { id -> TextButton(onClick = { onOpenSong(id) }) { Text("Open the original song") } }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { dialog = RecipeDialog.NewSong }) { Text("New song from recipe") }
                OutlinedButton(onClick = { dialog = RecipeDialog.Adapt }) { Text("Adapt") }
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Style", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { RelayLauncher.copy(context, "Style", r.styleText) }) { Text("Copy") }
                    }
                    SelectionContainer { Text(r.styleText.ifBlank { "Empty" }, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Settings", style = MaterialTheme.typography.titleSmall)
                    r.settings.describe().forEach { (label, value) -> Text("$label: ${value ?: "Suno's default"}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Lyrics", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showLyrics = !showLyrics }) { Text(if (showLyrics) "Hide" else "Show") }
                    }
                    if (showLyrics) {
                        Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            SelectionContainer { Text(r.lyricsText.ifBlank { "Empty" }, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            if (state.adaptations.isNotEmpty()) {
                Text("Adaptations", style = MaterialTheme.typography.titleMedium)
                state.adaptations.forEach { a ->
                    ListItem(
                        headlineContent = { Text(a.name) },
                        supportingContent = { a.settings.model?.let { Text(it) } },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        modifier = Modifier.clickable { onOpenRecipe(a.id) },
                    )
                }
            }
        }
    }

    val recipe = ui?.recipe
    when (dialog) {
        null -> Unit
        RecipeDialog.NewSong -> if (recipe != null) NewSongDialog(recipe, onDismiss = { dialog = null }) { title, artist, structure ->
            vm.newSong(title, artist, structure)
            dialog = null
        }
        RecipeDialog.Adapt -> if (recipe != null) AdaptDialog(recipe, onDismiss = { dialog = null }) { name, style, settings ->
            vm.adapt(name, style, settings)
            dialog = null
        }
        RecipeDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${recipe?.name}?") },
            text = { Text("The song and its snapshot are kept. Recipes adapted from this one are kept too.") },
            confirmButton = { TextButton(onClick = { dialog = null; vm.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NewSongDialog(recipe: Recipe, onDismiss: () -> Unit, onCreate: (String, String, Boolean) -> Unit) {
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf(recipe.artist) }
    var structure by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New song from ${recipe.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Copies the style prompt and settings. Genre tags come too.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Artist") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { structure = !structure }) {
                    Checkbox(checked = structure, onCheckedChange = { structure = it })
                    Text("Copy the lyric structure (section tags only, no words)")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(title, artist, structure) }, enabled = title.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Adapt a recipe for a newer Suno model: a new recipe, named by the guide's convention ("Recipe 12 – v6"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdaptDialog(recipe: Recipe, onDismiss: () -> Unit, onSave: (String, String, SunoSettings) -> Unit) {
    var model by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(RecipeNames.adapted(recipe.name, "")) }
    var nameEdited by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(recipe.styleText) }
    fun setModel(m: String) {
        model = m
        if (!nameEdited) name = RecipeNames.adapted(recipe.name, m)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adapt ${recipe.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The original stays exactly as it is. This saves a new recipe linked to it.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = model, onValueChange = ::setModel, label = { Text("New Suno model") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SunoSettings.MODEL_SUGGESTIONS.forEach { m -> SuggestionChip(onClick = { setModel(m) }, label = { Text(m) }) }
                }
                OutlinedTextField(value = name, onValueChange = { name = it; nameEdited = true }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    value = style,
                    onValueChange = { style = it },
                    label = { Text("Style prompt") },
                    supportingText = { Text("Change what the new model needs; ${style.length} characters.") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, style, recipe.settings.copy(model = model.trim().ifEmpty { recipe.settings.model })) },
                enabled = name.isNotBlank() && style.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
