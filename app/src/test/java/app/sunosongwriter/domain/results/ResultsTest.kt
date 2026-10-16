package app.sunosongwriter.domain.results

import app.sunosongwriter.domain.model.Snapshot
import app.sunosongwriter.domain.model.SongStatus
import app.sunosongwriter.domain.model.SunoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueCatalogTest {
    @Test
    fun builtInsFollowTheSpecOrderAndCustomTagsComeLast() {
        val catalog = IssueCatalog.merge(listOf(StoredFix("Robotic vocals", "more human phrasing", builtIn = false), StoredFix("Airy", "", builtIn = false)))
        assertEquals(14, catalog.count { it.isBuiltIn })
        assertEquals("muddy-mix", catalog.first().tag)
        assertEquals(listOf("Airy", "Robotic vocals"), catalog.filter { !it.isBuiltIn }.map { it.label })
    }

    @Test
    fun anEditedBuiltInKeepsItsLabelAndIsMarkedEdited() {
        val catalog = IssueCatalog.merge(listOf(StoredFix("distant-vocals", "dry, upfront vocal", builtIn = true)))
        val fix = catalog.single { it.tag == "distant-vocals" }
        assertEquals("Distant vocals", fix.label)
        assertEquals("dry, upfront vocal", fix.fixText)
        assertTrue(fix.isEdited)
        assertFalse(catalog.single { it.tag == "muddy-mix" }.isEdited)
    }

    @Test
    fun customTagNamesAreTrimmedAndCantClash() {
        val catalog = IssueCatalog.merge(emptyList())
        assertEquals("Robotic vocals", IssueCatalog.customTagName("  Robotic   vocals ", catalog))
        assertNull(IssueCatalog.customTagName("lyrics CUT off", catalog))
        assertNull(IssueCatalog.customTagName("   ", catalog))
    }

    @Test
    fun aDeletedCustomTagStillHasALabel() {
        assertEquals("Gone tag", IssueCatalog.label("Gone tag", IssueCatalog.merge(emptyList())))
    }
}

class ResultsBriefTest {
    private val catalog = IssueCatalog.merge(listOf(StoredFix("Robotic vocals", "", builtIn = false)))

    private fun take(index: Int, rating: Int?, issues: List<String>, notes: String = "") =
        Take(index.toLong(), 1, index, "https://suno.com/s/$index", rating, notes, issues, 0)

    @Test
    fun describesEachTakeThenTheFixesForEveryIssue() {
        val text = ResultsBrief.describe(
            listOf(take(2, null, listOf("distant-vocals")), take(1, 2, listOf("lyrics-cut-off", "distant-vocals"), notes = "cut off mid-chorus")),
            catalog,
        )
        assertEquals(
            """
            Take 1 (rated 2/5):
            Issues: Distant vocals, Lyrics cut off
            Notes: cut off mid-chorus

            Take 2:
            Issues: Distant vocals

            Suggested fixes (from the Suno v6 guide and what has worked before):
            - Distant vocals: Add to the Vocals line: close-mic lead vocals, intimate vocal presence.
            - Lyrics cut off: Add an instrumental buffer after the final vocal section: [Instrumental Outro], then [Hard Stop] and [End]. Ending line: complete every written lyric before a short instrumental outro and final hard stop.
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun issuesFollowTheCatalogOrderAndTagsWithoutAFixAreListedOnlyOnTheTake() {
        val takes = listOf(take(1, 3, listOf("Robotic vocals", "lyrics-cut-off", "muddy-mix")))
        assertEquals(listOf("muddy-mix", "lyrics-cut-off", "Robotic vocals"), ResultsBrief.issueTags(takes, catalog))
        val text = ResultsBrief.describe(takes, catalog)
        assertTrue("Issues: Muddy mix, Lyrics cut off, Robotic vocals" in text)
        assertFalse("- Robotic vocals" in text)
    }

    @Test
    fun aTakeWithNothingNotedSaysSo() {
        assertEquals("Take 1 (rated 5/5):\nNo issues noted.", ResultsBrief.describe(listOf(take(1, 5, emptyList())), catalog))
    }
}

class SunoLinksTest {
    @Test
    fun extractsTheLinkFromSharedText() {
        assertEquals("https://suno.com/s/AbC123", SunoLinks.extract("Listen to Tin Roof on Suno: https://suno.com/s/AbC123."))
        assertEquals("take one", SunoLinks.extract("  take one "))
        assertNull(SunoLinks.extract("   "))
        assertTrue(SunoLinks.isWebLink("https://suno.com/song/x"))
        assertFalse(SunoLinks.isWebLink("take one"))
    }

    @Test
    fun variantNameComesFromTheSnapshotLabel() {
        fun snap(label: String) = Snapshot(1, 1, 1, label, "", "", SunoSettings(), 0)
        assertEquals("Consensus", snap("Consensus v5").variantName)
        assertEquals("Framework-refined 2", snap("Framework-refined 2 v1").variantName)
        assertEquals("Odd", snap("Odd").variantName)
    }

    @Test
    fun songStatusShowsTheFurthestStep() {
        assertEquals(SongStatus.DRAFT, SongStatus.of(sent = false, hasResults = false, isRecipe = false))
        assertEquals(SongStatus.SENT, SongStatus.of(sent = true, hasResults = false, isRecipe = false))
        assertEquals(SongStatus.HAS_RESULTS, SongStatus.of(sent = true, hasResults = true, isRecipe = false))
        assertEquals(SongStatus.RECIPE, SongStatus.of(sent = true, hasResults = true, isRecipe = true))
    }
}

class RecipesTest {
    @Test
    fun sectionTagsKeepTheStructureWithoutTheWords() {
        val lyrics = """
            [Instrumental Intro]

            [Verse]
            Rain on the roof
            (ooh)
            [Chorus]
            Tin roof, tin roof

            [Instrumental Outro]
            [Hard Stop]
            [End]
        """.trimIndent()
        assertEquals("[Instrumental Intro]\n\n[Verse]\n\n[Chorus]\n\n[Instrumental Outro]\n\n[Hard Stop]\n\n[End]", LyricStructure.sectionTags(lyrics))
        assertEquals("", LyricStructure.sectionTags("just words\nno tags"))
    }

    @Test
    fun adaptedNamesFollowTheGuidesConvention() {
        assertEquals("Recipe 12 – v6", RecipeNames.adapted("Recipe 12", "v6 Standard"))
        assertEquals("Recipe 12 – v7", RecipeNames.adapted("Recipe 12", " v7 "))
        assertEquals("Recipe 12 (adapted)", RecipeNames.adapted("Recipe 12", ""))
        assertEquals("Recipe 3", RecipeNames.next(2))
    }

    @Test
    fun filterByTagArtistAndRating() {
        fun recipe(tags: List<String>, artist: String, rating: Int?) =
            Recipe(1, "R", null, null, null, "", artist, tags, "", "", SunoSettings(), rating, 0)
        val r = recipe(listOf("Pop-Punk", "emo"), "The Gutters", 4)
        assertTrue(RecipeFilter(tag = "pop-punk").matches(r))
        assertTrue(RecipeFilter(artist = "the gutters", minRating = 4).matches(r))
        assertFalse(RecipeFilter(minRating = 5).matches(r))
        assertFalse(RecipeFilter(minRating = 1).matches(recipe(emptyList(), "", null)))
        assertFalse(RecipeFilter(tag = "metal").matches(r))
    }
}

class WinRatesTest {
    private fun snap(id: Long, songId: Long, label: String) = Snapshot(id, songId, id, label, "", "", SunoSettings(), id)
    private fun take(snapshotId: Long, rating: Int?) = Take(snapshotId * 10, snapshotId, 1, null, rating, "", emptyList(), 0)

    private val snapshots = listOf(
        snap(1, 1, "Consensus v3"), snap(2, 1, "Framework-refined v1"),
        snap(3, 2, "Consensus v2"), snap(4, 2, "Framework-refined v1"),
        snap(5, 3, "Consensus v1"), snap(6, 3, "Framework-refined v1"),
    )
    private val takes = listOf(take(1, 3), take(2, 5), take(3, 4), take(4, 4), take(6, null))
    private val winners = mapOf(1L to "Framework-refined", 2L to "Consensus")
    private val tags = mapOf(1L to listOf("pop-punk"), 2L to listOf("Pop-Punk", "emo"), 3L to listOf("metal"))

    @Test
    fun winRatesCountOnlyDecidedSongs() {
        val stats = WinRates.compute(snapshots, takes, winners, tags).associateBy { it.name }
        val consensus = stats.getValue("Consensus")
        assertEquals(2, consensus.decided)
        assertEquals(1, consensus.wins)
        assertEquals(0.5, consensus.winRate!!, 0.0001)
        assertEquals(2, consensus.takes)
        assertEquals(3.5, consensus.averageRating!!, 0.0001)
        assertEquals(4.5, stats.getValue("Framework-refined").averageRating!!, 0.0001)
    }

    @Test
    fun filteringByGenreTagIgnoresCase() {
        val stats = WinRates.compute(snapshots, takes, winners, tags, tag = "emo")
        assertEquals(listOf("Consensus", "Framework-refined"), stats.map { it.name })
        assertEquals(1.0, stats.first().winRate!!, 0.0001)

        val metal = WinRates.compute(snapshots, takes, winners, tags, tag = "metal")
        assertTrue(metal.all { it.decided == 0 && it.winRate == null })
    }
}
