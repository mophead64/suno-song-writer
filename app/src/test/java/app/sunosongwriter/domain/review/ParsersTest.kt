package app.sunosongwriter.domain.review

import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StylePrompt
import app.sunosongwriter.domain.model.StyleSection
import app.sunosongwriter.domain.model.SunoSettings
import app.sunosongwriter.domain.prompt.SessionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyParserTest {
    private val base = SongContent(
        style = StylePrompt(mapOf(StyleSection.GENRE to "pop-punk")),
        lyrics = "[Verse]\nold line",
        settings = SunoSettings(weirdness = 30),
    )

    @Test
    fun readsTheRequestedFormat() {
        val reply = """
            STYLE:
            Genre: pop-punk, easycore
            Vocals: gritty male cleans
            LYRICS:
            [Verse]
            new line
            [Instrumental Outro]
            [End]
            SETTINGS:
            Weirdness: 40%
            Style Influence: 90%
            NOTE:
            Added easycore and an outro.
        """.trimIndent()

        val parsed = ReplyParser.parse(reply, base, SessionAction.IMPLEMENT)!!

        assertEquals("pop-punk, easycore", parsed.content.style[StyleSection.GENRE])
        assertEquals("[Verse]\nnew line\n[Instrumental Outro]\n[End]", parsed.content.lyrics)
        assertEquals(SunoSettings(weirdness = 40, styleInfluence = 90), parsed.content.settings)
        assertEquals("Added easycore and an outro.", parsed.note)
        assertEquals(ParsedContent.Part.entries.toSet(), parsed.changedParts)
    }

    @Test
    fun acceptsMarkdownHeadingsAndCodeFencesAndKeepsMissingParts() {
        val reply = "Here you go!\n\n## Lyrics\n```\n[Chorus]\nWe are still here\n```\n\n**Note:** tightened the chorus"

        val parsed = ReplyParser.parse(reply, base, SessionAction.TWEAK)!!

        assertEquals("[Chorus]\nWe are still here", parsed.content.lyrics)
        assertEquals(base.style, parsed.content.style)
        assertEquals("tightened the chorus", parsed.note)
        assertEquals(setOf(ParsedContent.Part.LYRICS), parsed.changedParts)
    }

    @Test
    fun noHeadingsMeansTheWholeReplyIsTheRequestedPart() {
        val lyrics = ReplyParser.parse("[Verse]\nRaindrops on the roof", base, SessionAction.WRITE_LYRICS)!!
        val style = ReplyParser.parse("Genre: darkwave\nVocals: airy", base, SessionAction.WRITE_STYLE)!!

        assertEquals("[Verse]\nRaindrops on the roof", lyrics.content.lyrics)
        assertEquals("darkwave", style.content.style[StyleSection.GENRE])
        assertNull(ReplyParser.parse("Sounds great to me!", base, SessionAction.IMPLEMENT))
    }

    @Test
    fun lyricLinesStartingWithHeadingWordsAreNotHeadings() {
        val reply = "LYRICS:\n[Verse]\nLyrics of a lonely song\nNotes on the breeze\n"

        assertEquals("[Verse]\nLyrics of a lonely song\nNotes on the breeze", ReplyParser.parse(reply, base, SessionAction.TWEAK)!!.content.lyrics)
    }
}

class ReviewParserTest {
    @Test
    fun parsesTheRequestedJson() {
        val review = ReviewParser.parse(
            """{"verdict":"changes","summary":"Strong hook, weak verse.","suggestions":[{"target":"lyrics","section":"Verse 1","change":"Replace 'tonight' with a concrete image","rationale":"cliché"}]}""",
        )!!

        assertEquals(Verdict.CHANGES, review.verdict)
        assertEquals("Strong hook, weak verse.", review.summary)
        assertEquals(ParsedSuggestion(SuggestionTarget.LYRICS, "Verse 1", "Replace 'tonight' with a concrete image", "cliché"), review.suggestions.single())
    }

    @Test
    fun toleratesFencesSurroundingTextTrailingCommasAndOtherFieldNames() {
        val reply = """
            Sure! Here's my review:
            ```json
            {
              verdict: "changes",
              changes: [
                {"area": "Style prompt", "where": "Vocals", "suggestion": "Add 'close-mic lead vocals'", "reason": "vocals sound distant",},
                "Shorten the bridge",
              ],
            }
            ```
            Hope that helps!
        """.trimIndent()

        val review = ReviewParser.parse(reply)!!

        assertEquals(2, review.suggestions.size)
        assertEquals(SuggestionTarget.STYLE, review.suggestions[0].target)
        assertEquals("Vocals", review.suggestions[0].section)
        assertEquals("vocals sound distant", review.suggestions[0].rationale)
        assertEquals(ParsedSuggestion(SuggestionTarget.OTHER, null, "Shorten the bridge", null), review.suggestions[1])
    }

    @Test
    fun approvalWithNoSuggestions() {
        assertEquals(Verdict.APPROVE, ReviewParser.parse("""{"verdict": "Approved", "suggestions": []}""")!!.verdict)
        assertEquals(Verdict.APPROVE, ReviewParser.parse("""{"summary": "Ready to go."}""")!!.verdict)
    }

    @Test
    fun returnsNullWithoutUsableJson() {
        assertNull(ReviewParser.parse("I think the chorus could be stronger."))
        assertNull(ReviewParser.parse("""{"foo": 1}"""))
        assertNull(ReviewParser.parse("{ broken"))
    }
}

class SuggestionGroupingTest {
    private fun s(id: Long, who: String, change: String, section: String? = "Chorus", target: SuggestionTarget = SuggestionTarget.LYRICS) =
        Suggestion(id, id, who, target, section, change, null, Decision.PENDING, null, null)

    @Test
    fun mergesSimilarSuggestionsFromDifferentParticipants() {
        val groups = SuggestionGrouping.group(
            listOf(
                s(1, "Gemini", "Make the chorus hook shorter and punchier"),
                s(2, "Claude", "Make the chorus hook punchier and shorter"),
                s(3, "Llama", "Add a key change before the final chorus", section = "Bridge"),
                s(4, "Gemini", "Shorter and punchier chorus hook please"),
            ),
        )

        assertEquals(3, groups.size)
        assertEquals(listOf("Gemini", "Claude"), groups[0].backers)
        assertTrue(groups.none { g -> g.suggestions.map { it.participantName }.let { it.size != it.toSet().size } })
    }

    @Test
    fun differentSectionsOrTargetsStaySeparate() {
        val groups = SuggestionGrouping.group(
            listOf(s(1, "A", "make it punchier"), s(2, "B", "make it punchier", section = "Verse"), s(3, "C", "make it punchier", target = SuggestionTarget.STYLE)),
        )
        assertEquals(3, groups.size)
    }

    @Test
    fun describesAcceptedSuggestionsForImplement() {
        val text = SuggestionGrouping.describeForImplement(
            listOf(s(1, "A", "original").copy(editedChange = "user's version", rationale = "clearer")),
        )
        assertEquals("1. [Lyrics · Chorus] user's version (why: clearer)", text)
    }
}
