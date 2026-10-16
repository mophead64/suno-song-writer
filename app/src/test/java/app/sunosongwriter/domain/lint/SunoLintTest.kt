package app.sunosongwriter.domain.lint

import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StylePrompt
import app.sunosongwriter.domain.model.StyleSection
import app.sunosongwriter.domain.model.SunoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunoLintTest {
    private val goodLyrics = """
        [Intro]
        I kept the lights on all night
        [Chorus]
        WE ARE STILL HERE
        (Still here)
        [Final Chorus]
        We are still breathing
        [Instrumental Outro]
        [Hard Stop]
        [End]
    """.trimIndent()

    private val goodStyle = StylePrompt(
        mapOf(
            StyleSection.GENRE to "Modern pop-punk",
            StyleSection.VOCALS to "gritty male cleans",
            StyleSection.DRUMS to "driving punk drums",
            StyleSection.PRODUCTION to "high-fidelity studio production",
            StyleSection.ENDING to "complete every written lyric before a short instrumental outro and final hard stop",
        ),
    )

    @Test
    fun aSongFollowingTheGuideIsClean() {
        assertEquals(emptyList<LintIssue>(), SunoLint.check(SongContent(goodStyle, goodLyrics, SunoSettings(weirdness = 35))))
    }

    @Test
    fun missingBufferAndStopIsFlagged() {
        val issue = SunoLint.instrumentalBuffer("[Verse]\nla la\n[Chorus]\nlast line")!!

        assertTrue(issue.message.contains("[Instrumental Outro]"))
        assertTrue(issue.message.contains("[Hard Stop] and [End]"))
        assertNull(SunoLint.instrumentalBuffer(goodLyrics))
        assertNotNull(SunoLint.instrumentalBuffer("[Verse]\nla\n[Instrumental Outro]")) // no stop
    }

    @Test
    fun linesAfterTheEndAreFlagged() {
        assertNotNull(SunoLint.linesAfterEnd("[Verse]\nla\n[End]\nthis won't be sung"))
        assertNull(SunoLint.linesAfterEnd(goodLyrics))
    }

    @Test
    fun undefinedIntroIsFlagged() {
        assertNotNull(SunoLint.intro("[Intro]\n[Verse]\nfirst line"))
        assertNull(SunoLint.intro("[Instrumental Intro]\n[Verse]\nfirst line"))
        assertNull(SunoLint.intro("[Intro]\nfirst line sung straight away"))
        assertNull(SunoLint.intro("[Verse]\nfirst line"))
        assertNull(SunoLint.intro("first line with no tags"))
    }

    @Test
    fun longTagsUseTheConfiguredLimit() {
        val lyrics = "[Verse with soaring layered harmonies and a slow build]\nla"

        assertNotNull(SunoLint.longTags(lyrics, SunoLimits(tagWords = 6)))
        assertNull(SunoLint.longTags(lyrics, SunoLimits(tagWords = 12)))
    }

    @Test
    fun directionsInParenthesesAreFlaggedButBackingVocalsAreNot() {
        assertNotNull(SunoLint.sungDirections("(Vocals: Clear, direct, and emotive)\nla"))
        assertNotNull(SunoLint.sungDirections("I walk alone (fade in rain)"))
        assertNull(SunoLint.sungDirections("WE ARE STILL HERE\n(Still here)\n(ooh, ooh)"))
    }

    @Test
    fun styleChecks() {
        assertNotNull(SunoLint.missingCoreSections(StylePrompt(mapOf(StyleSection.GENRE to "pop"))))
        assertNull(SunoLint.missingCoreSections(goodStyle))
        assertNotNull(SunoLint.endingInstruction(StylePrompt(mapOf(StyleSection.GENRE to "pop"))))
        assertNull(SunoLint.endingInstruction(StylePrompt(mapOf(StyleSection.ARRANGEMENT to "ends with a final hard stop"))))
        assertNotNull(SunoLint.styleOrder(StylePrompt.fromFreeText("Drums: fast\nGenre: punk")))
        assertNull(SunoLint.styleOrder(StylePrompt.fromFreeText("Genre: punk,\nmelodic\nDrums: fast")))
    }

    @Test
    fun lengthsUseTheLimits() {
        val limits = SunoLimits(styleChars = 20, lyricsChars = 10)

        assertNotNull(SunoLint.styleLength(goodStyle, limits))
        assertNotNull(SunoLint.lyricsLength("eleven char", limits))
        assertEquals("lyrics-empty", SunoLint.lyricsLength("   ", limits)!!.rule)
    }

    @Test
    fun lyricLinesWrittenAsTagsAreFlagged() {
        val allBracketed = "[Intro]\n[Verse 1]\n[I hear the rain on the roof tonight]\n[I'm trying to fill the emptiness]"

        assertTrue(SunoLint.lyricsInBrackets(allBracketed)!!.message.contains("nothing will be sung"))
        assertNotNull(SunoLint.lyricsInBrackets("[Verse]\nla\n[I hear the rain tonight]\n[Voice takes the lead now]"))
        assertNull(SunoLint.lyricsInBrackets(goodLyrics))
        assertNull(SunoLint.lyricsInBrackets("[Verse 2]\nla\n[Pre-Chorus]\nla\n[Guitar Solo]\n[Final Chorus]\nla"))
    }

    @Test
    fun weirdnessOutsideTheSweetSpotIsOnlyInformational() {
        assertEquals(LintLevel.INFO, SunoLint.weirdness(80)!!.level)
        assertNull(SunoLint.weirdness(40))
        assertNull(SunoLint.weirdness(null))
    }

    @Test
    fun cleanCopyNormalisesWhitespaceOnly() {
        assertEquals("[Verse]\nline one\n\nline two", SunoLint.cleanLyrics("  [Verse]  \r\nline one   \r\n\r\n\r\n\r\nline two\r\n\r\n"))
    }
}
