package app.sunosongwriter.domain.results

import app.sunosongwriter.domain.model.Snapshot
import app.sunosongwriter.domain.model.SunoSettings

/** A snapshot that worked, saved exactly as it was sent (spec 5.14). Never edited. */
data class Recipe(
    val id: Long,
    val name: String,
    val snapshotId: Long?,
    val songId: Long?,
    /** The recipe this one adapts for a newer Suno model ("Recipe 12" → "Recipe 12 – v6"). */
    val parentRecipeId: Long?,
    val songTitle: String,
    val artist: String,
    val tags: List<String>,
    val styleText: String,
    val lyricsText: String,
    val settings: SunoSettings,
    /** Best take's rating: live from the results while the snapshot exists, else as saved. */
    val rating: Int?,
    val createdAt: Long,
)

data class RecipeFilter(
    val tag: String? = null,
    val artist: String? = null,
    /** Only recipes rated at least this. */
    val minRating: Int? = null,
) {
    val isActive: Boolean get() = tag != null || artist != null || minRating != null

    fun matches(r: Recipe): Boolean =
        (tag == null || r.tags.any { it.equals(tag, ignoreCase = true) }) &&
            (artist == null || r.artist.equals(artist, ignoreCase = true)) &&
            (minRating == null || (r.rating ?: 0) >= minRating)
}

object RecipeNames {
    /** "Recipe 12", numbered after the recipes that already exist. */
    fun next(existing: Int): String = "Recipe ${existing + 1}"

    /** The guide's convention: adapting "Recipe 12" for v6 saves "Recipe 12 – v6". */
    fun adapted(name: String, sunoModel: String): String {
        val model = sunoModel.trim().substringBefore(' ').ifBlank { sunoModel.trim() }
        return if (model.isEmpty()) "$name (adapted)" else "$name – $model"
    }
}

object LyricStructure {
    private val tag = Regex("""^\[[^\]]+]$""")

    /**
     * Keeps only the section tags of a recipe's lyrics, one block per section, so a new song can reuse
     * the structure (intro, buffer, end tags) with new words (spec 5.14).
     */
    fun sectionTags(lyrics: String): String =
        lyrics.lines().map { it.trim() }.filter { tag.matches(it) }.joinToString("\n\n")
}

/** How one variant name (e.g. "Consensus") has done across songs (spec 5.15). */
data class VariantStats(
    val name: String,
    /** Songs with a winner chosen in which this variant was sent. */
    val decided: Int,
    val wins: Int,
    val takes: Int,
    val averageRating: Double?,
) {
    val winRate: Double? get() = if (decided == 0) null else wins.toDouble() / decided
}

object WinRates {
    /**
     * Win rates by variant name, optionally for songs with one genre tag. A song counts towards a
     * variant once it has a winner and a snapshot of that variant. Ratings average every rated take of
     * the variant's snapshots, decided or not.
     */
    fun compute(
        snapshots: List<Snapshot>,
        takes: List<Take>,
        winners: Map<Long, String>,
        songTags: Map<Long, List<String>>,
        tag: String? = null,
    ): List<VariantStats> {
        val included = snapshots.filter { s -> tag == null || songTags[s.songId].orEmpty().any { it.equals(tag, ignoreCase = true) } }
        val takesBySnapshot = takes.groupBy { it.snapshotId }
        return included.groupBy { it.variantName }.map { (name, snaps) ->
            val songs = snaps.map { it.songId }.toSet()
            val decided = songs.filter { it in winners }
            val variantTakes = snaps.flatMap { takesBySnapshot[it.id].orEmpty() }
            val ratings = variantTakes.mapNotNull { it.rating }
            VariantStats(
                name = name,
                decided = decided.size,
                wins = decided.count { winners[it] == name },
                takes = variantTakes.size,
                averageRating = ratings.takeIf { it.isNotEmpty() }?.average(),
            )
        }.sortedWith(compareByDescending<VariantStats> { it.winRate ?: -1.0 }.thenByDescending { it.decided }.thenBy { it.name.lowercase() })
    }
}
