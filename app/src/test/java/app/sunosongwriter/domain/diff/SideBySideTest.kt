package app.sunosongwriter.domain.diff

import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StylePrompt
import app.sunosongwriter.domain.model.StyleSection
import app.sunosongwriter.domain.model.SunoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SideBySideTest {
    private fun text(segments: List<Segment>?) = segments?.joinToString("") { it.text }

    @Test
    fun linesLineUpWithBlanksWhereOnlyOneSideHasThem() {
        val rows = SideBySide.content(
            SongContent(lyrics = "[Verse]\nRain on the roof\n[End]"),
            SongContent(lyrics = "[Verse]\nRain on the tin roof\n[Instrumental Outro]\n[End]"),
        ).lyrics
        assertEquals(listOf("[Verse]", "Rain on the roof", null, "[End]"), rows.map { text(it.left) })
        assertEquals(listOf("[Verse]", "Rain on the tin roof", "[Instrumental Outro]", "[End]"), rows.map { text(it.right) })
        assertEquals(listOf(false, true, true, false), rows.map { it.changed })
    }

    @Test
    fun unpairedRemovalsAndAdditionsShareRows() {
        val rows = SideBySide.lyrics(listOf(DiffLine.Removed("a"), DiffLine.Removed("b"), DiffLine.Added("c"), DiffLine.Same("d")))
        assertEquals(listOf("a", "b", "d"), rows.map { text(it.left) })
        assertEquals(listOf("c", null, "d"), rows.map { text(it.right) })
    }

    @Test
    fun styleShowsEverySectionEitherSideHasInTheGuidesOrder() {
        val left = SongContent(style = StylePrompt(mapOf(StyleSection.GENRE to "pop-punk", StyleSection.VOCALS to "gritty")))
        val right = SongContent(
            style = StylePrompt(mapOf(StyleSection.GENRE to "pop-punk", StyleSection.VOCALS to "close-mic gritty", StyleSection.ENDING to "hard stop")),
            settings = SunoSettings(weirdness = 40),
        )
        val sbs = SideBySide.content(left, right)
        assertEquals(listOf(StyleSection.GENRE, StyleSection.VOCALS, StyleSection.ENDING), sbs.style.map { it.section })
        assertFalse(sbs.style[0].row.changed)
        assertEquals("gritty", text(sbs.style[1].row.left))
        assertEquals("close-mic gritty", text(sbs.style[1].row.right))
        assertNull(sbs.style[2].row.left)
        assertTrue(sbs.settings.any { it.label == "Weirdness" && it.old == null && it.new == "40%" })
        assertEquals(4, sbs.settings.size)
    }
}
