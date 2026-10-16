package app.sunosongwriter.domain.workflow

import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.domain.prompt.TemplateKey
import app.sunosongwriter.domain.review.Decision
import app.sunosongwriter.domain.review.Suggestion
import app.sunosongwriter.domain.review.SuggestionGroup
import app.sunosongwriter.domain.review.SuggestionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChairTest {
    private fun proposal(id: Long, name: String, content: String) = SessionMessage(
        id = id, sessionId = 1, round = 1, action = SessionAction.WRITE_STYLE, participantId = id, participantName = name,
        participantAvatar = "", modelLabel = null, templateKey = TemplateKey.builtIn("write-style"), instructions = null, prompt = "",
        content = content, status = MessageStatus.DONE, error = null, basedOnVersionId = null, producedVersionId = null, verdict = null,
        summary = null, parseFailed = false, inputTokens = null, outputTokens = null, createdAt = 0, proposal = true,
    )

    private fun group(change: String, vararg backers: String) = SuggestionGroup(
        backers.mapIndexed { i, b -> Suggestion(i.toLong(), 1, b, SuggestionTarget.LYRICS, "Chorus", change, "because", Decision.PENDING, null, null) },
    )

    @Test
    fun theMostDetailedProposalWins() {
        val proposals = listOf(
            proposal(1, "Short", "STYLE:\nGenre: pop-punk, with a very long and wordy genre description that goes on and on"),
            proposal(2, "Detailed", "STYLE:\nGenre: pop-punk\nVocals: gritty\nDrums: fast"),
            proposal(3, "Broken", ""),
        )
        assertEquals(2L, ChairRules.mostDetailedProposal(proposals))
        assertNull(ChairRules.mostDetailedProposal(listOf(proposal(3, "Broken", ""))))
    }

    @Test
    fun autoAcceptNeedsEnoughBackersButNeverMoreThanTheReviewers() {
        val both = group("Add thunder", "A", "B")
        val one = group("Blast beats", "A")
        assertEquals(listOf(both), ChairRules.autoAccept(listOf(both, one), ChairRules.backersNeeded(2, reviewers = 3)))
        assertEquals(1, ChairRules.backersNeeded(2, reviewers = 1))
        assertEquals(1, ChairRules.backersNeeded(0, reviewers = 4))
    }

    @Test
    fun choicesAreNumberedForTheChair() {
        val text = ChairRules.describeSuggestions(listOf(group("Add thunder", "A", "B"), group("Blast beats", "A")))
        assertTrue("1. [Lyrics · Chorus] Add thunder Why: because (backed by 2: A, B)" in text)
        assertTrue("2. [Lyrics · Chorus] Blast beats" in text)
        assertTrue("\"accept\"" in text)
        val styles = ChairRules.describeProposals(listOf(proposal(5, "Gemini", "STYLE:\nGenre: emo")))
        assertTrue("1. From Gemini:\nGenre: emo" in styles)
    }

    @Test
    fun parsesTheChairsDecisionTolerantly() {
        assertEquals(ChairDecision(pick = 2), ChairParser.parse("```json\n{\"pick\": 2, \"reason\": \"best\"}\n```"))
        assertEquals(ChairDecision(accept = listOf(1, 3)), ChairParser.parse("Sure! {\"accept\": [1, 3], \"reason\": \"ok\"}"))
        assertEquals(ChairDecision(accept = emptyList()), ChairParser.parse("{\"accept\": []}"))
        assertEquals(ChairDecision(pick = 1), ChairParser.parse("Pick: 1"))
        assertNull(ChairParser.parse("I like them all"))
    }
}
