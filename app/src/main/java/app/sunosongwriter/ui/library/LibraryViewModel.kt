package app.sunosongwriter.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sunosongwriter.AppContainer
import app.sunosongwriter.domain.model.Folder
import app.sunosongwriter.domain.model.LibraryFilter
import app.sunosongwriter.domain.model.SongStatus
import app.sunosongwriter.domain.model.SongSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUi(
    val songs: List<SongSummary>,
    val totalSongs: Int,
    val folders: List<Folder>,
    val artists: List<String>,
    val tags: List<String>,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    private val _filter = MutableStateFlow(LibraryFilter())
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /** Song ids matching the search box, re-run when songs change so edits show up; null = no query. */
    private val searchHits = combine(_filter.map { it.query }.distinctUntilChanged().debounce(200), c.songs.songs) { q, _ -> q }
        .mapLatest { query -> if (query.isBlank()) null else c.songs.search(query).orEmpty() }

    val ui: StateFlow<LibraryUi?> = combine(c.songs.songs, c.songs.folders, _filter, searchHits, c.snapshots.sentSongIds) { songs, folders, filter, hits, sent ->
        val folderNames = folders.associate { it.id to it.name }
        LibraryUi(
            songs = songs
                .filter { filter.matches(it) && (hits == null || it.id in hits) }
                .map { SongSummary(it, it.folderId?.let(folderNames::get), if (it.id in sent) SongStatus.SENT else SongStatus.DRAFT) },
            totalSongs = songs.size,
            folders = folders,
            artists = songs.map { it.artist }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() },
            tags = songs.flatMap { it.tags }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun updateFilter(change: (LibraryFilter) -> LibraryFilter) = _filter.update(change)

    fun clearMessage() {
        _message.value = null
    }

    fun export(uri: Uri) = viewModelScope.launch {
        _message.value = try {
            val text = c.libraryTransfer.export()
            withContext(Dispatchers.IO) {
                c.application.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(text) }
            }
            "Library exported (${ui.value?.totalSongs ?: 0} songs). API keys are never included."
        } catch (e: Exception) {
            "Export failed: ${e.message}"
        }
    }

    fun import(uri: Uri) = viewModelScope.launch {
        _message.value = try {
            val text = withContext(Dispatchers.IO) {
                c.application.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            }
            val result = c.libraryTransfer.import(text)
            buildString {
                append("Imported ${result.songsAdded} songs")
                if (result.songsSkipped > 0) append(", skipped ${result.songsSkipped} already in the library")
                if (result.foldersAdded > 0) append(", added ${result.foldersAdded} folders")
                append(".")
            }
        } catch (e: Exception) {
            "Import failed: ${e.message}"
        }
    }
}
