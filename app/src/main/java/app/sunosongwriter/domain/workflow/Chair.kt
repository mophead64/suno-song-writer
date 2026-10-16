package app.sunosongwriter.domain.workflow

import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StyleSection
import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.domain.review.ReplyParser
import app.sunosongwriter.domain.review.SuggestionGroup
import kotlinx.serialization.Serializable

/** Who makes the Writers' Room's decisions (spec 5.5; open question Q2). */
@Serializable
enum class ChairMode(val label: String) {
    /** The run pauses at each decision and notifies the user. */
    YOU("You"),

    /** Suggestions backed by enough reviewers are accepted; the most detailed style proposal is picked. */
    AUTO("Automatic"),

    /** A nominated participant decides, through a chair step. */
    MODEL("A participant"),
}

/** A chair's decision: which style proposal (1-based), or which suggestion groups to accept (1-based). */
data class ChairDecision(val pick: Int? = null, val accept: List<Int>? = null)

object ChairRules {
    /**
     * The most detailed proposal: the most filled-in style sections, then the longest. Spec section 2's
     * workflow keeps "who gave the best detailed style". Returns the message id, or null if none parse.
     */
    fun mostDetailedProposal(proposals: List<SessionMessage>): Long? =
        proposals.mapNotNull { m ->
            val style = ReplyParser.parse(m.content, SongContent(), SessionAction.WRITE_STYLE)?.content?.style ?: return@mapNotNull null
            val sections = StyleSection.entries.count { it != StyleSection.NOTES && style[it].isNotBlank() }
            Triple(m.id, sections, style.flatText().length)
        }.maxWithOrNull(compareBy<Triple<Long, Int, Int>> { it.second }.thenBy { it.third })?.first

    /**
     * Backers needed for an automatic accept, never more than the reviewers who answered, so a lone
     * reviewer's suggestions can still be accepted.
     */
    fun backersNeeded(threshold: Int, reviewers: Int): Int = threshold.coerceIn(1, maxOf(1, reviewers))

    /** Groups to accept automatically. Groups the user already decided on are left alone. */
    fun autoAccept(groups: List<SuggestionGroup>, needed: Int): List<SuggestionGroup> = groups.filter { it.backers.size >= needed }

    /** The choices a chair model sees for the style pick, numbered from 1. */
    fun describeProposals(proposals: List<SessionMessage>): String = buildString {
        append("Style proposals from the team. Pick the one that best describes the finished recording for Suno: a clear genre identity, specific vocals, instruments described by what they're doing, arrangement, production and an ending.\n\n")
        proposals.forEachIndexed { i, m ->
            val style = ReplyParser.parse(m.content, SongContent(), SessionAction.WRITE_STYLE)?.content?.style?.flatText() ?: m.content.trim()
            append(i + 1).append(". From ").append(m.participantName).append(":\n").append(style).append("\n\n")
        }
        append("""Reply with ONLY a JSON object: {"pick": <number>, "reason": "one sentence"}""")
    }

    /** The choices a chair model sees for a round's suggestions, numbered from 1. */
    fun describeSuggestions(groups: List<SuggestionGroup>): String = buildString {
        append("Suggested changes from this round's reviews. Accept the ones that make the song better in Suno; reject ones that repeat, conflict, or make it worse.\n\n")
        groups.forEachIndexed { i, g ->
            val s = g.lead
            append(i + 1).append(". [").append(listOfNotNull(s.target.label, s.section).joinToString(" · ")).append("] ").append(s.effectiveChange)
            s.rationale?.let { append(" Why: ").append(it) }
            append(" (backed by ").append(g.backers.size).append(": ").append(g.backers.joinToString()).append(")\n")
        }
        append("""
Reply with ONLY a JSON object: {"accept": [numbers of the changes to apply], "reason": "one sentence"}. Use an empty list to accept none.""")
    }
}

object ChairParser {
    private val pick = Regex(""""?pick"?\s*[:=]\s*"?(\d{1,3})""", RegexOption.IGNORE_CASE)
    private val accept = Regex(""""?accept"?\s*[:=]\s*\[([^\]]*)]""", RegexOption.IGNORE_CASE)
    private val number = Regex("""\d{1,3}""")

    /** Reads `{"pick": 2}` or `{"accept": [1, 3]}`, tolerating code fences and extra text. Null if neither is there. */
    fun parse(reply: String): ChairDecision? {
        val p = pick.find(reply)?.groupValues?.get(1)?.toIntOrNull()
        val a = accept.find(reply)?.groupValues?.get(1)?.let { list -> number.findAll(list).map { it.value.toInt() }.toList() }
        if (p == null && a == null) return null
        return ChairDecision(p, a)
    }
}
