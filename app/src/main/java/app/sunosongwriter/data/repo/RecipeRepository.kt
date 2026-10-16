package app.sunosongwriter.data.repo

import app.sunosongwriter.data.db.RecipeDao
import app.sunosongwriter.data.db.RecipeEntity
import app.sunosongwriter.domain.model.Author
import app.sunosongwriter.domain.model.Snapshot
import app.sunosongwriter.domain.model.Song
import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StylePrompt
import app.sunosongwriter.domain.model.SunoSettings
import app.sunosongwriter.domain.results.LyricStructure
import app.sunosongwriter.domain.results.Recipe
import app.sunosongwriter.domain.results.RecipeNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Proven recipes (spec 5.14). Recipes are only ever inserted (or deleted), never edited. */
class RecipeRepository(
    private val dao: RecipeDao,
    private val songs: SongRepository,
    private val json: Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    val recipes: Flow<List<Recipe>> = combine(dao.observeAll(), dao.observeBestRatings()) { rows, ratings ->
        rows.map { toDomain(it, it.snapshotId?.let(ratings::get)) }
    }

    fun observeForSnapshot(snapshotId: Long): Flow<Recipe?> =
        combine(dao.observeForSnapshot(snapshotId), dao.observeBestRatings()) { row, ratings -> row?.let { toDomain(it, ratings[snapshotId]) } }

    /** Song ids with at least one recipe, for the library badge. */
    val songIds: Flow<Set<Long>> = dao.observeAll().map { rows -> rows.mapNotNull { it.songId }.toSet() }

    suspend fun nextName(): String = RecipeNames.next(dao.count())

    /** Saves the snapshot exactly as it was sent, with a copy of the song's details. */
    suspend fun saveFromSnapshot(snapshot: Snapshot, song: Song, name: String, bestRating: Int?): Long = dao.insert(
        RecipeEntity(
            name = name.trim(), snapshotId = snapshot.id, songId = song.id, parentRecipeId = null,
            songTitle = song.title, artist = song.artist, tagsJson = json.encodeToString(song.tags),
            styleText = snapshot.styleText, lyricsText = snapshot.lyricsText, settingsJson = json.encodeToString(snapshot.settings),
            ratingAtSave = bestRating, createdAt = nowMillis(),
        ),
    )

    /**
     * Adapting a recipe for a new Suno model saves a new recipe linked to the original, which stays as
     * it was (the guide's "Recipe 12" / "Recipe 12 – v6").
     */
    suspend fun adapt(original: Recipe, name: String, styleText: String, settings: SunoSettings): Long = dao.insert(
        RecipeEntity(
            name = name.trim(), snapshotId = null, songId = original.songId, parentRecipeId = original.id,
            songTitle = original.songTitle, artist = original.artist, tagsJson = json.encodeToString(original.tags),
            styleText = styleText.trim(), lyricsText = original.lyricsText, settingsJson = json.encodeToString(settings),
            ratingAtSave = null, createdAt = nowMillis(),
        ),
    )

    suspend fun get(id: Long): Recipe? = recipes.first().firstOrNull { it.id == id }

    suspend fun delete(id: Long) = dao.delete(id)

    /**
     * A new song from the recipe's style and settings, and optionally its lyric structure (section tags
     * only). The style keeps the recipe's exact text.
     */
    suspend fun newSong(recipe: Recipe, title: String, artist: String, withStructure: Boolean, folderId: Long? = null): Long {
        val songId = songs.createSong(title, artist, concept = "", tags = recipe.tags, folderId = folderId)
        val variant = songs.observeVariants(songId).first().first()
        val content = SongContent(
            style = StylePrompt.fromFreeText(recipe.styleText),
            lyrics = if (withStructure) LyricStructure.sectionTags(recipe.lyricsText) else "",
            settings = recipe.settings,
        )
        songs.saveVersion(variant.id, content, Author.USER, "From recipe “${recipe.name}”")
        return songId
    }

    private fun toDomain(e: RecipeEntity, liveRating: Int?) = Recipe(
        id = e.id, name = e.name, snapshotId = e.snapshotId, songId = e.songId, parentRecipeId = e.parentRecipeId,
        songTitle = e.songTitle, artist = e.artist,
        tags = runCatching { json.decodeFromString<List<String>>(e.tagsJson) }.getOrDefault(emptyList()),
        styleText = e.styleText, lyricsText = e.lyricsText,
        settings = runCatching { json.decodeFromString<SunoSettings>(e.settingsJson) }.getOrDefault(SunoSettings()),
        rating = liveRating ?: e.ratingAtSave, createdAt = e.createdAt,
    )
}
