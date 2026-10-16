package app.sunosongwriter.ui.song

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sunosongwriter.data.repo.SnapshotRepository
import app.sunosongwriter.data.repo.SongRepository
import app.sunosongwriter.domain.model.Author
import app.sunosongwriter.domain.model.Song
import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StylePrompt
import app.sunosongwriter.domain.model.Variant
import app.sunosongwriter.domain.model.Version
import app.sunosongwriter.ui.SongRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SongUi(
    val song: Song,
    val variants: List<Variant>,
    val variant: Variant,
    /** Newest version of the selected variant. */
    val current: Version,
    /** The selected variant's versions, newest first. */
    val history: List<Version>,
)

/** Unsaved edits. One edit session becomes one version when saved. */
data class SongEdit(
    val content: SongContent,
    val styleAsText: Boolean,
    val styleText: String,
) {
    fun resolved(): SongContent =
        if (styleAsText) content.copy(style = StylePrompt.fromFreeText(styleText)) else content
}

class SongViewModel(savedState: SavedStateHandle, private val songs: SongRepository, snapshotRepo: SnapshotRepository) : ViewModel() {
    val songId = savedState.toRoute<SongRoute>().songId

    val snapshots = snapshotRepo.observeForSong(songId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedVariantId = MutableStateFlow<Long?>(null)
    private val _edit = MutableStateFlow<SongEdit?>(null)
    val edit: StateFlow<SongEdit?> = _edit.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    val ui: StateFlow<SongUi?> = combine(
        songs.observeSong(songId),
        songs.observeVariants(songId),
        songs.observeVersions(songId),
        selectedVariantId,
    ) { song, variants, versions, selected ->
        if (song == null || variants.isEmpty()) return@combine null
        val variant = variants.firstOrNull { it.id == selected } ?: variants.first()
        val history = versions.filter { it.variantId == variant.id }.sortedByDescending { it.number }
        if (history.isEmpty()) return@combine null
        SongUi(song, variants, variant, history.first(), history)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectVariant(id: Long) {
        if (_edit.value == null) selectedVariantId.value = id
    }

    fun clearMessage() {
        _message.value = null
    }

    // Editing

    fun startEdit() {
        val current = ui.value?.current ?: return
        val style = current.content.style
        _edit.value = SongEdit(current.content, styleAsText = style.freeText != null, styleText = style.flatText())
    }

    fun cancelEdit() {
        _edit.value = null
    }

    fun updateEdit(change: (SongEdit) -> SongEdit) = _edit.update { it?.let(change) }

    /** Switches between per-section fields and one block of text, carrying the text across. */
    fun setStyleAsText(asText: Boolean) = updateEdit { e ->
        when {
            asText == e.styleAsText -> e
            asText -> e.copy(styleAsText = true, styleText = e.content.style.flatText())
            else -> e.copy(styleAsText = false, content = e.content.copy(style = StylePrompt(StylePrompt.parse(e.styleText))))
        }
    }

    val hasUnsavedChanges: Boolean
        get() = _edit.value?.let { it.resolved() != ui.value?.current?.content } == true

    fun save(note: String) {
        val edit = _edit.value ?: return
        val variant = ui.value?.variant ?: return
        viewModelScope.launch {
            val saved = songs.saveVersion(variant.id, edit.resolved(), Author.USER, note.ifBlank { "Edited" })
            _edit.value = null
            _message.value = saved?.let { "Saved as v${it.number}" } ?: "No changes to save."
        }
    }

    // Variants

    fun createVariant(name: String, copyCurrent: Boolean) {
        val state = ui.value ?: return
        viewModelScope.launch {
            selectedVariantId.value = songs.createVariant(songId, name, if (copyCurrent) state.current.id else null)
        }
    }

    fun renameVariant(name: String) {
        val variant = ui.value?.variant ?: return
        viewModelScope.launch { songs.renameVariant(variant.id, name) }
    }

    fun deleteVariant() {
        val variant = ui.value?.variant ?: return
        viewModelScope.launch {
            if (songs.deleteVariant(variant.id)) {
                selectedVariantId.value = null
                _message.value = "Deleted ${variant.name}."
            } else {
                _message.value = "A song needs at least one variant."
            }
        }
    }
}
