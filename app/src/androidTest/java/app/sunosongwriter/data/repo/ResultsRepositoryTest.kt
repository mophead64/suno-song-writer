package app.sunosongwriter.data.repo

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sunosongwriter.data.db.AppDatabase
import app.sunosongwriter.domain.model.Author
import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StylePrompt
import app.sunosongwriter.domain.model.SunoSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResultsRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var now = 1_000L
    private val songs = SongRepository(db, json) { now++ }
    private val snapshots = SnapshotRepository(db.snapshotDao(), json) { now++ }
    private val results = ResultRepository(db, snapshots) { now++ }
    private val recipes = RecipeRepository(db.recipeDao(), songs, json) { now++ }

    @After
    fun tearDown() = db.close()

    private val content = SongContent(
        style = StylePrompt.fromFreeText("Genre: pop-punk\nVocals: gritty"),
        lyrics = "[Instrumental Intro]\n[Verse]\nRain on the roof\n[Chorus]\nTin roof\n[Instrumental Outro]\n[Hard Stop]\n[End]",
        settings = SunoSettings(model = "v5", weirdness = 40, styleInfluence = 90),
    )

    /** A song with a Consensus variant sent once. Returns (song id, snapshot id). */
    private suspend fun sentSong(): Pair<Long, Long> {
        val songId = songs.createSong("Tin Roof", "The Gutters", "", listOf("pop-punk", "emo"), null)
        val variantId = songs.createVariant(songId, "Consensus", null)
        val v = songs.saveVersion(variantId, content, Author.USER, "")!!
        return songId to snapshots.markSent(songId, v, "Consensus v2")
    }

    @Test
    fun takesAreNumberedAndKeepTheirIssueTags() = runTest {
        val (songId, snapshotId) = sentSong()
        val first = results.addTake(snapshotId, " https://suno.com/s/1 ", 7, " cut off ", listOf("lyrics-cut-off", "distant-vocals", "lyrics-cut-off"))
        results.addTake(snapshotId, null, null, "", emptyList())

        val takes = results.observeTakes(snapshotId).first()
        assertEquals(listOf(1, 2), takes.map { it.index })
        assertEquals("https://suno.com/s/1", takes[0].link)
        assertEquals(5, takes[0].rating)
        assertEquals("cut off", takes[0].notes)
        assertEquals(listOf("distant-vocals", "lyrics-cut-off"), takes[0].issues)
        assertEquals(setOf(songId), results.songIdsWithResults.first())

        results.updateTake(first, "", 3, "fine now", listOf("muddy-mix"))
        val updated = results.takes(snapshotId).first()
        assertNull(updated.link)
        assertEquals(listOf("muddy-mix"), updated.issues)

        results.deleteTake(first)
        assertEquals(1, results.takes(snapshotId).size)
    }

    @Test
    fun theWinnerIsTheSnapshotsVariantAndCanBeReplaced() = runTest {
        val (songId, snapshotId) = sentSong()
        val snapshot = snapshots.get(snapshotId)!!
        results.markWinner(snapshot)
        assertEquals("Consensus", results.observeWinner(songId).first()!!.variantName)
        assertEquals(mapOf(songId to "Consensus"), results.winners.first())

        results.clearWinner(songId)
        assertNull(results.observeWinner(songId).first())
    }

    @Test
    fun fixesCanBeEditedResetAndExtendedWithCustomTags() = runTest {
        results.saveFix("distant-vocals", "upfront dry vocal")
        assertEquals("upfront dry vocal", results.catalog().single { it.tag == "distant-vocals" }.fixText)

        results.resetFix("distant-vocals")
        assertTrue(results.catalog().single { it.tag == "distant-vocals" }.fixText.startsWith("Add to the Vocals line"))

        // Saving the guide's own text isn't an override.
        results.saveFix("muddy-mix", results.catalog().single { it.tag == "muddy-mix" }.fixText)
        assertTrue(db.resultDao().fixes().isEmpty())

        assertEquals("Robotic vocals", results.addCustomTag("Robotic vocals", "more human phrasing"))
        assertNull(results.addCustomTag("robotic VOCALS"))
        assertEquals("more human phrasing", results.catalog().single { it.tag == "Robotic vocals" }.fixText)
        results.deleteCustomTag("Robotic vocals")
        assertTrue(results.catalog().none { it.tag == "Robotic vocals" })
        // Built-ins can't be deleted.
        results.deleteCustomTag("muddy-mix")
        assertNotNull(results.catalog().singleOrNull { it.tag == "muddy-mix" })
    }

    @Test
    fun recipesKeepTheSentTextAndUseTheLiveRating() = runTest {
        val (songId, snapshotId) = sentSong()
        val song = songs.observeSong(songId).first()!!
        val snapshot = snapshots.get(snapshotId)!!
        val takeId = results.addTake(snapshotId, null, 3, "", emptyList())
        assertEquals("Recipe 1", recipes.nextName())
        val id = recipes.saveFromSnapshot(snapshot, song, " Recipe 1 ", bestRating = 3)

        results.updateTake(takeId, null, 5, "", emptyList())
        val recipe = recipes.get(id)!!
        assertEquals("Recipe 1", recipe.name)
        assertEquals(5, recipe.rating)
        assertEquals(snapshot.styleText, recipe.styleText)
        assertEquals(listOf("emo", "pop-punk"), recipe.tags.sorted())
        assertEquals(setOf(songId), recipes.songIds.first())

        // The song going doesn't take the recipe with it; the saved rating stays.
        results.updateTake(takeId, null, 4, "", emptyList())
        songs.deleteSong(songId)
        val orphan = recipes.get(id)!!
        assertEquals(3, orphan.rating)
        assertEquals("Tin Roof", orphan.songTitle)
    }

    @Test
    fun adaptingSavesANewLinkedRecipeAndLeavesTheOriginal() = runTest {
        val (songId, snapshotId) = sentSong()
        val original = recipes.get(recipes.saveFromSnapshot(snapshots.get(snapshotId)!!, songs.observeSong(songId).first()!!, "Recipe 12", null))!!

        val adaptedId = recipes.adapt(original, "Recipe 12 – v6", "Genre: pop-punk\nVocals: gritty, close-mic", original.settings.copy(model = "v6 Standard"))

        val adapted = recipes.get(adaptedId)!!
        assertEquals(original.id, adapted.parentRecipeId)
        assertEquals("v6 Standard", adapted.settings.model)
        assertNull(adapted.snapshotId)
        assertEquals(original, recipes.get(original.id))
    }

    @Test
    fun newSongFromRecipeCopiesStyleSettingsAndOptionallyTheStructure() = runTest {
        val (songId, snapshotId) = sentSong()
        val recipe = recipes.get(recipes.saveFromSnapshot(snapshots.get(snapshotId)!!, songs.observeSong(songId).first()!!, "Recipe 1", null))!!

        val withStructure = recipes.newSong(recipe, "Gutter Rain", "The Gutters", withStructure = true)
        val draft = songs.latest(songs.observeVariants(withStructure).first().single().id)!!
        assertEquals(recipe.styleText, draft.content.style.flatText())
        assertEquals(recipe.settings, draft.content.settings)
        assertEquals("[Instrumental Intro]\n\n[Verse]\n\n[Chorus]\n\n[Instrumental Outro]\n\n[Hard Stop]\n\n[End]", draft.content.lyrics)
        assertEquals("From recipe “Recipe 1”", draft.changeNote)
        assertEquals(listOf("emo", "pop-punk"), songs.observeSong(withStructure).first()!!.tags)

        val bare = recipes.newSong(recipe, "Dry Spell", "", withStructure = false)
        assertEquals("", songs.latest(songs.observeVariants(bare).first().single().id)!!.content.lyrics)
    }

    @Test
    fun resultsWinnersRecipesAndFixesSurviveExportAndImport() = runTest {
        val (songId, snapshotId) = sentSong()
        results.addTake(snapshotId, "https://suno.com/s/1", 4, "good", listOf("distant-vocals"))
        results.markWinner(snapshots.get(snapshotId)!!)
        results.addCustomTag("Robotic vocals", "more human")
        val original = recipes.get(recipes.saveFromSnapshot(snapshots.get(snapshotId)!!, songs.observeSong(songId).first()!!, "Recipe 1", 4))!!
        recipes.adapt(original, "Recipe 1 – v6", original.styleText, original.settings)
        val exported = LibraryTransfer(db, songs, json) { now }.export()

        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val targetSongs = SongRepository(target, json) { now }
        val transfer = LibraryTransfer(target, targetSongs, json) { now }
        transfer.import(exported)
        transfer.import(exported)

        val targetSnapshots = SnapshotRepository(target.snapshotDao(), json)
        val targetResults = ResultRepository(target, targetSnapshots)
        val targetRecipes = RecipeRepository(target.recipeDao(), targetSongs, json)
        val song = targetSongs.songs.first().single()
        val snap = targetSnapshots.observeForSong(song.id).first().single()
        val take = targetResults.takes(snap.id).single()
        assertEquals(listOf("distant-vocals"), take.issues)
        assertEquals(4, take.rating)
        assertEquals(snap.id, targetResults.observeWinner(song.id).first()!!.snapshotId)
        assertTrue(targetResults.catalog().any { it.tag == "Robotic vocals" })
        val imported = targetRecipes.recipes.first()
        assertEquals(2, imported.size)
        val importedOriginal = imported.single { it.name == "Recipe 1" }
        assertEquals(snap.id, importedOriginal.snapshotId)
        assertEquals(song.id, importedOriginal.songId)
        assertEquals(importedOriginal.id, imported.single { it.name == "Recipe 1 – v6" }.parentRecipeId)
        target.close()
    }
}
