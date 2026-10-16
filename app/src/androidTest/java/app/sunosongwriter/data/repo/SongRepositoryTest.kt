package app.sunosongwriter.data.repo

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sunosongwriter.data.db.AppDatabase
import app.sunosongwriter.domain.model.Author
import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StylePrompt
import app.sunosongwriter.domain.model.StyleSection
import app.sunosongwriter.domain.model.SunoSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var now = 1_000L
    private val repo = SongRepository(db, json) { now++ }

    @After
    fun tearDown() = db.close()

    private suspend fun newSong(title: String = "Tin Roof", folderId: Long? = null) =
        repo.createSong(title, "The Gutters", "rain, regret", listOf("pop-punk", "Pop-Punk", " emo "), folderId)

    private suspend fun draftVariant(songId: Long) = repo.observeVariants(songId).first().single()

    @Test
    fun createSongMakesADraftVariantWithAnEmptyFirstVersion() = runTest {
        val songId = newSong()

        val song = repo.observeSong(songId).first()!!
        val variant = draftVariant(songId)
        val versions = repo.observeVersions(songId).first()

        assertEquals(listOf("emo", "pop-punk"), song.tags) // trimmed, de-duplicated ignoring case
        assertEquals("Draft", variant.name)
        assertEquals(1, versions.single().number)
        assertEquals(SongContent(), versions.single().content)
    }

    @Test
    fun savingVersionsNumbersThemAndLinksParents() = runTest {
        val variant = draftVariant(newSong())
        val v2 = repo.saveVersion(variant.id, SongContent(lyrics = "one"), Author.USER, "first")!!
        val v3 = repo.saveVersion(variant.id, SongContent(lyrics = "two"), Author.USER, "second")!!

        assertEquals(2, v2.number)
        assertEquals(3, v3.number)
        assertEquals(v2.id, v3.parentVersionId)
        assertNull(repo.saveVersion(variant.id, SongContent(lyrics = "two"), Author.USER, "no change"))
    }

    @Test
    fun contentRoundTripsThroughTheDatabase() = runTest {
        val variant = draftVariant(newSong())
        val content = SongContent(
            style = StylePrompt(mapOf(StyleSection.GENRE to "darkwave", StyleSection.ENDING to "hard stop")),
            lyrics = "[Verse]\nline",
            settings = SunoSettings(model = "v6 Standard", weirdness = 35, styleInfluence = 90, variety = 0),
        )

        repo.saveVersion(variant.id, content, Author.USER, "")

        assertEquals(content, repo.latest(variant.id)!!.content)
    }

    @Test
    fun searchFindsSongsByLyricTitleArtistAndTagPrefix() = runTest {
        val rain = newSong("Tin Roof")
        val other = newSong("Elsewhere")
        repo.saveVersion(draftVariant(rain).id, SongContent(lyrics = "Raindrops are fallin' on my tin roof tonight"), Author.USER, "")

        assertEquals(setOf(rain), repo.search("raindrop"))
        assertEquals(setOf(rain), repo.search("tin ROOF"))
        assertEquals(setOf(rain, other), repo.search("gutters"))
        assertEquals(setOf(rain, other), repo.search("pop-punk"))
        assertNull(repo.search("  ?! "))
    }

    @Test
    fun searchOnlyUsesTheLatestLyrics() = runTest {
        val songId = newSong()
        val variant = draftVariant(songId)
        repo.saveVersion(variant.id, SongContent(lyrics = "umbrella"), Author.USER, "")
        repo.saveVersion(variant.id, SongContent(lyrics = "sunshine"), Author.USER, "")

        assertEquals(emptySet<Long>(), repo.search("umbrella"))
        assertEquals(setOf(songId), repo.search("sunshine"))
    }

    @Test
    fun restoreSavesACopyOfAnOldVersionAsTheNewest() = runTest {
        val variant = draftVariant(newSong())
        val v2 = repo.saveVersion(variant.id, SongContent(lyrics = "old"), Author.USER, "")!!
        repo.saveVersion(variant.id, SongContent(lyrics = "new"), Author.USER, "")

        val restored = repo.restore(v2.id)!!

        assertEquals(4, restored.number)
        assertEquals("old", restored.content.lyrics)
        assertEquals("Restored v2", restored.changeNote)
    }

    @Test
    fun newVariantStartsFromACopyAndTheLastVariantCannotBeDeleted() = runTest {
        val songId = newSong()
        val draft = draftVariant(songId)
        val v2 = repo.saveVersion(draft.id, SongContent(lyrics = "base"), Author.USER, "")!!

        val consensus = repo.createVariant(songId, "Consensus", v2.id)

        val first = repo.latest(consensus)!!
        assertEquals("base", first.content.lyrics)
        assertEquals(v2.id, first.parentVersionId)
        assertEquals("Started from Draft v2", first.changeNote)
        assertTrue(repo.deleteVariant(consensus))
        assertFalse(repo.deleteVariant(draft.id))
    }

    @Test
    fun deletingAFolderUnfilesItsSongs() = runTest {
        val folder = repo.createFolder("Pop-punk")
        val songId = newSong(folderId = folder)

        repo.deleteFolder(folder)

        assertNull(repo.observeSong(songId).first()!!.folderId)
    }

    @Test
    fun exportThenImportIntoAnEmptyLibraryRecreatesEverythingAndIsIdempotent() = runTest {
        val folder = repo.createFolder("Ideas")
        val songId = newSong(folderId = folder)
        val draft = draftVariant(songId)
        val v2 = repo.saveVersion(draft.id, SongContent(lyrics = "hello"), Author.USER, "edit")!!
        repo.createVariant(songId, "Consensus", v2.id)
        val exported = LibraryTransfer(db, repo, json) { now }.export()

        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val targetRepo = SongRepository(target, json) { now }
        val transfer = LibraryTransfer(target, targetRepo, json) { now }
        val first = transfer.import(exported)
        val second = transfer.import(exported)

        assertEquals(ImportResult(songsAdded = 1, songsSkipped = 0, foldersAdded = 1), first)
        assertEquals(ImportResult(songsAdded = 0, songsSkipped = 1, foldersAdded = 0), second)
        val imported = targetRepo.songs.first().single()
        assertEquals(listOf("emo", "pop-punk"), imported.tags)
        assertEquals("Ideas", targetRepo.folders.first().single { it.id == imported.folderId }.name)
        val versions = targetRepo.observeVersions(imported.id).first()
        assertEquals(3, versions.size)
        val consensusFirst = versions.last()
        assertEquals(versions.single { it.content.lyrics == "hello" && it.number == 2 }.id, consensusFirst.parentVersionId)
        assertEquals(setOf(imported.id), targetRepo.search("hello"))
        assertFalse(exported.contains("apiKey"))
        target.close()
    }
}
