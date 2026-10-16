package app.sunosongwriter.domain.model

import kotlinx.serialization.Serializable

data class Folder(val id: Long, val name: String)

data class Song(
    val id: Long,
    val folderId: Long?,
    val title: String,
    val artist: String,
    val concept: String,
    val tags: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A labelled branch of a song, e.g. `Draft`, `Consensus`, `Framework-refined` (spec 4). */
data class Variant(val id: Long, val songId: Long, val name: String, val createdAt: Long)

/** Everything that goes into Suno: style prompt, lyrics and slider settings (spec 4). */
data class SongContent(
    val style: StylePrompt = StylePrompt(),
    val lyrics: String = "",
    val settings: SunoSettings = SunoSettings(),
)

/**
 * Suno's controls that can't be pasted, so the handoff screen shows them as a checklist (spec 4, 5.9).
 * Null means "not set / leave at Suno's default".
 */
@Serializable
data class SunoSettings(
    val model: String? = null,
    val weirdness: Int? = null,
    val styleInfluence: Int? = null,
    val variety: Int? = null,
) {
    fun describe(): List<Pair<String, String?>> = listOf(
        "Model" to model,
        "Weirdness" to weirdness?.let { "$it%" },
        "Style Influence" to styleInfluence?.let { "$it%" },
        "Variety" to variety?.toString(),
    )

    companion object {
        val MODEL_SUGGESTIONS = listOf("v6 Standard", "v6 Max")
    }
}

enum class AuthorType { USER, MODEL }

data class Author(
    val type: AuthorType,
    /** Display name kept on the version, so history still reads correctly if the participant is deleted. */
    val label: String,
    val participantId: Long? = null,
) {
    companion object {
        val USER = Author(AuthorType.USER, "You")
    }
}

/** An immutable state of a variant's content (spec 4). Every change creates a new one. */
data class Version(
    val id: Long,
    val variantId: Long,
    /** 1, 2, 3… within the variant. */
    val number: Int,
    val parentVersionId: Long?,
    val content: SongContent,
    val author: Author,
    /** Which workflow step produced it (e.g. "review round 2"); null for manual edits. */
    val step: String?,
    val changeNote: String,
    val createdAt: Long,
)

/** Library list status (spec 5.3). Results and recipe arrive in iteration 6. */
enum class SongStatus(val label: String) { DRAFT("Draft"), SENT("Sent") }

/** What was copied into Suno, frozen (spec 5.11). */
data class Snapshot(
    val id: Long,
    val songId: Long,
    val versionId: Long,
    val versionLabel: String,
    val styleText: String,
    val lyricsText: String,
    val settings: SunoSettings,
    val sentAt: Long,
)

data class SongSummary(
    val song: Song,
    val folderName: String?,
    val status: SongStatus,
)

data class LibraryFilter(
    val query: String = "",
    /** Null: all folders. [UNFILED]: songs without a folder. */
    val folderId: Long? = null,
    val artist: String? = null,
    val tag: String? = null,
) {
    val isActive: Boolean get() = query.isNotBlank() || folderId != null || artist != null || tag != null

    fun matches(song: Song): Boolean =
        (folderId == null || (folderId == UNFILED && song.folderId == null) || song.folderId == folderId) &&
            (artist == null || song.artist.equals(artist, ignoreCase = true)) &&
            (tag == null || song.tags.any { it.equals(tag, ignoreCase = true) })

    companion object {
        const val UNFILED = -1L
    }
}
