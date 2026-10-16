package app.sunosongwriter.domain.diff

import app.sunosongwriter.domain.diff.Segment.Kind.ADDED
import app.sunosongwriter.domain.diff.Segment.Kind.REMOVED
import app.sunosongwriter.domain.diff.Segment.Kind.SAME
import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StylePrompt
import app.sunosongwriter.domain.model.StyleSection
import app.sunosongwriter.domain.model.SunoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDiffTest {
    @Test
    fun wordDiffKeepsWhitespaceSoSegmentsRejoin() {
        val segments = TextDiff.words("rain on a tin roof", "rain on the tin roof tonight")

        assertEquals("rain on a tin roof", segments.filter { it.kind != ADDED }.joinToString("") { it.text })
        assertEquals("rain on the tin roof tonight", segments.filter { it.kind != REMOVED }.joinToString("") { it.text })
        assertTrue(Segment("a", REMOVED) in segments)
        assertTrue(Segment("the", ADDED) in segments)
    }

    @Test
    fun identicalTextIsAllSame() {
        assertEquals(listOf(Segment("same words", SAME)), TextDiff.words("same words", "same words"))
        assertTrue(TextDiff.lines("a\nb", "a\nb").all { it is DiffLine.Same })
    }

    @Test
    fun lineDiffPairsReplacedLinesAndKeepsPureInsertions() {
        val old = """
            [Verse]
            I kept the lights on all night
            Waiting for something to change
            [Chorus]
        """.trimIndent()
        val new = """
            [Verse]
            I kept the lights on all night long
            Waiting for something to change
            [Pre-Chorus]
            Maybe tomorrow
            [Chorus]
        """.trimIndent()

        val diff = TextDiff.lines(old, new)

        assertEquals(DiffLine.Same("[Verse]"), diff[0])
        val changed = diff[1] as DiffLine.Changed
        assertEquals("I kept the lights on all night", changed.old)
        assertTrue(changed.newSegments.any { it.kind == ADDED && it.text.contains("long") })
        assertEquals(DiffLine.Same("Waiting for something to change"), diff[2])
        assertEquals(listOf(DiffLine.Added("[Pre-Chorus]"), DiffLine.Added("Maybe tomorrow")), diff.subList(3, 5))
        assertEquals(DiffLine.Same("[Chorus]"), diff[5])
    }

    @Test
    fun removedLinesAreReported() {
        assertEquals(listOf(DiffLine.Same("a"), DiffLine.Removed("b")), TextDiff.lines("a\nb", "a"))
        assertEquals(listOf(DiffLine.Added("first line")), TextDiff.lines("", "first line"))
    }

    @Test
    fun contentDiffCoversStyleSectionsLyricsAndSettings() {
        val old = SongContent(
            style = StylePrompt(mapOf(StyleSection.GENRE to "pop-punk", StyleSection.BASS to "active bass")),
            lyrics = "line one",
            settings = SunoSettings(model = "v6 Standard", weirdness = 30),
        )
        val new = old.copy(
            style = old.style.with(StyleSection.GENRE, "pop-punk, easycore"),
            settings = old.settings.copy(weirdness = 45),
        )

        val diff = TextDiff.content(old, new)

        assertEquals(listOf(StyleSection.GENRE), diff.style.map { it.section })
        assertEquals(false, diff.lyricsChanged)
        assertEquals(listOf(SettingDiff("Weirdness", "30%", "45%")), diff.settings)
        assertTrue(TextDiff.content(old, old).isEmpty)
    }
}
