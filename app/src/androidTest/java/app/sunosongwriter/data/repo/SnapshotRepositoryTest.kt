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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapshotRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var now = 1_000L
    private val songs = SongRepository(db, json) { now++ }
    private val snapshots = SnapshotRepository(db.snapshotDao(), json) { now++ }

    @After
    fun tearDown() = db.close()

    private val content = SongContent(
        style = StylePrompt(mapOf(StyleSection.GENRE to "pop-punk", StyleSection.VOCALS to "gritty")),
        lyrics = "[Verse]  \r\nRain on the roof\r\n\r\n\r\n[End]",
        settings = SunoSettings(weirdness = 35, styleInfluence = 90),
    )

    @Test
    fun markSentFreezesTheCleanTextAndLaterEditsDontChangeIt() = runTest {
        val songId = songs.createSong("Tin Roof", "", "", emptyList(), null)
        val variant = songs.observeVariants(songId).first().single()
        val v2 = songs.saveVersion(variant.id, content, Author.USER, "")!!

        val id = snapshots.markSent(songId, v2, "Draft v2")
        songs.saveVersion(variant.id, content.copy(lyrics = "completely different"), Author.USER, "")

        val snap = snapshots.get(id)!!
        assertEquals("Genre: pop-punk\nVocals: gritty", snap.styleText)
        assertEquals("[Verse]\nRain on the roof\n\n[End]", snap.lyricsText)
        assertEquals(SunoSettings(weirdness = 35, styleInfluence = 90), snap.settings)
        assertEquals("Draft v2", snap.versionLabel)
        assertEquals(setOf(songId), snapshots.sentSongIds.first())
    }

    @Test
    fun snapshotsSurviveExportAndImportLinkedToTheirVersions() = runTest {
        val songId = songs.createSong("Tin Roof", "The Gutters", "", emptyList(), null)
        val variant = songs.observeVariants(songId).first().single()
        val v2 = songs.saveVersion(variant.id, content, Author.USER, "")!!
        snapshots.markSent(songId, v2, "Draft v2")
        val exported = LibraryTransfer(db, songs, json) { now }.export()

        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val targetSongs = SongRepository(target, json) { now }
        LibraryTransfer(target, targetSongs, json) { now }.import(exported)

        val imported = targetSongs.songs.first().single()
        val snap = SnapshotRepository(target.snapshotDao(), json).observeForSong(imported.id).first().single()
        val versions = targetSongs.observeVersions(imported.id).first()
        assertTrue(versions.any { it.id == snap.versionId && it.number == 2 })
        assertEquals("[Verse]\nRain on the roof\n\n[End]", snap.lyricsText)
        target.close()
    }
}
