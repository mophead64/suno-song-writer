package app.sunosongwriter.domain.review

enum class Decision { PENDING, ACCEPTED, REJECTED }

/** A stored suggestion from one participant's review (spec 5.6). */
data class Suggestion(
    val id: Long,
    val messageId: Long,
    val participantName: String,
    val target: SuggestionTarget,
    val section: String?,
    val change: String,
    val rationale: String?,
    val decision: Decision,
    /** The user's rewording, when they chose "edit then accept". */
    val editedChange: String?,
    val implementedInVersionId: Long?,
) {
    val effectiveChange: String get() = editedChange ?: change
}

/** Suggestions that say the same thing, shown once with who backs them (spec 5.6). */
data class SuggestionGroup(val suggestions: List<Suggestion>) {
    val lead: Suggestion get() = suggestions.first()
    val backers: List<String> get() = suggestions.map { it.participantName }.distinct()
    val decision: Decision get() = lead.decision
    val ids: List<Long> get() = suggestions.map { it.id }
}

object SuggestionGrouping {
    private val word = Regex("""[\p{L}\p{N}']+""")
    private val stopWords = setOf("the", "a", "an", "to", "of", "and", "in", "on", "for", "with", "it", "make", "more", "be", "is", "that", "this")

    private fun words(text: String): Set<String> =
        word.findAll(text.lowercase()).map { it.value }.filter { it !in stopWords }.toSet()

    /** Jaccard similarity of meaningful words. */
    fun similarity(a: String, b: String): Double {
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        return wa.intersect(wb).size.toDouble() / wa.union(wb).size
    }

    /**
     * Groups suggestions with the same target and section (ignoring case) whose wording overlaps by at least
     * [threshold]. Suggestions from the same participant are never merged with each other.
     */
    fun group(suggestions: List<Suggestion>, threshold: Double = 0.5): List<SuggestionGroup> {
        val groups = mutableListOf<MutableList<Suggestion>>()
        for (s in suggestions) {
            val home = groups.firstOrNull { g ->
                val lead = g.first()
                lead.target == s.target &&
                    lead.section.orEmpty().equals(s.section.orEmpty(), ignoreCase = true) &&
                    g.none { it.participantName == s.participantName } &&
                    similarity(lead.change, s.change) >= threshold
            }
            if (home != null) home += s else groups += mutableListOf(s)
        }
        return groups.map(::SuggestionGroup)
    }

    /** Text for the Implement template's `{{suggestions}}`. */
    fun describeForImplement(accepted: List<Suggestion>): String =
        accepted.mapIndexed { i, s ->
            val where = listOfNotNull(s.target.label, s.section).joinToString(" · ")
            val why = s.rationale?.let { " (why: $it)" }.orEmpty()
            "${i + 1}. [$where] ${s.effectiveChange}$why"
        }.joinToString("\n")
}
