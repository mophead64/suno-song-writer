package app.sunosongwriter.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StylePromptTest {
    private val sections = mapOf(
        StyleSection.GENRE to "Modern pop-punk, melodic post-hardcore, easycore.",
        StyleSection.VOCALS to "gritty mature male baritone cleans, raspy melodic hooks.",
        StyleSection.PRODUCTION to "high-fidelity studio production, close-mic vocals, crisp transients.",
        StyleSection.ENDING to "complete every written lyric before a short instrumental outro and final hard stop.",
    )

    @Test
    fun rendersSectionsInGuideOrderWithLabels() {
        val shuffled = sections.entries.reversed().associate { it.key to it.value }

        assertEquals(
            """
            Genre: Modern pop-punk, melodic post-hardcore, easycore.
            Vocals: gritty mature male baritone cleans, raspy melodic hooks.
            Production: high-fidelity studio production, close-mic vocals, crisp transients.
            Ending: complete every written lyric before a short instrumental outro and final hard stop.
            """.trimIndent(),
            StylePrompt(shuffled).flatText(),
        )
    }

    @Test
    fun parseRoundTripsRenderedText() {
        assertEquals(sections, StylePrompt.parse(StylePrompt.render(sections)))
    }

    @Test
    fun parseAcceptsTheGuidesAlternativeLabelsAndMarkdown() {
        val parsed = StylePrompt.parse(
            """
            **Genre(s):** technical deathcore, cinematic horror
            Production Quality: high-fidelity studio production
            Ending Instructions: complete every written lyric
            Sliders: Weirdness: 32%. Style Influence: 90%.
            """.trimIndent(),
        )

        assertEquals("technical deathcore, cinematic horror", parsed[StyleSection.GENRE])
        assertEquals("high-fidelity studio production", parsed[StyleSection.PRODUCTION])
        // "Sliders:" isn't a style section, so it continues the previous one rather than being lost.
        assertEquals("complete every written lyric Sliders: Weirdness: 32%. Style Influence: 90%.", parsed[StyleSection.ENDING])
    }

    @Test
    fun parseIgnoresBulletAndNumberedListMarkers() {
        val parsed = StylePrompt.parse("- Genre: pop-punk\n* Vocals: clear, emotive\n• Drums: driving\n4. Bass: pulsing")

        assertEquals("pop-punk", parsed[StyleSection.GENRE])
        assertEquals("clear, emotive", parsed[StyleSection.VOCALS])
        assertEquals("driving", parsed[StyleSection.DRUMS])
        assertEquals("pulsing", parsed[StyleSection.BASS])
    }

    @Test
    fun parseUnwrapsBracketedLabelLines() {
        val parsed = StylePrompt.parse("[Vocals: Heartfelt, yearning delivery]\n[Drums: Pulsing beat]\n[Upbeat Pop-Punk]")

        assertEquals("Heartfelt, yearning delivery", parsed[StyleSection.VOCALS])
        assertEquals("Pulsing beat [Upbeat Pop-Punk]", parsed[StyleSection.DRUMS])
    }

    @Test
    fun unlabelledTextGoesToNotes() {
        val parsed = StylePrompt.parse("dreamy shoegaze, wall of guitars\nVocals: airy female")

        assertEquals("dreamy shoegaze, wall of guitars", parsed[StyleSection.NOTES])
        assertEquals("airy female", parsed[StyleSection.VOCALS])
        assertEquals("dreamy shoegaze, wall of guitars\nVocals: airy female", StylePrompt.render(parsed))
    }

    @Test
    fun freeTextIsKeptExactlyWhenItDoesNotMatchTheRendering() {
        val text = "Genre: darkwave,\n  synth-heavy\nVocals: low"

        val prompt = StylePrompt.fromFreeText(text)

        assertEquals(text, prompt.flatText())
        assertEquals("darkwave, synth-heavy", prompt[StyleSection.GENRE])
    }

    @Test
    fun freeTextMatchingTheRenderingIsStoredAsSectionsOnly() {
        assertNull(StylePrompt.fromFreeText(StylePrompt.render(sections)).freeText)
    }

    @Test
    fun editingASectionDropsFreeText() {
        val prompt = StylePrompt.fromFreeText("Genre: a,\nb").with(StyleSection.BASS, "active melodic bass")

        assertNull(prompt.freeText)
        assertEquals("Genre: a, b\nBass: active melodic bass", prompt.flatText())
    }
}
