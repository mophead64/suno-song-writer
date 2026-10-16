package app.sunosongwriter.domain.model

/** Turns what the user typed into a safe FTS4 MATCH expression: every word must match, as a prefix. */
object SearchQuery {
    private val word = Regex("""[\p{L}\p{N}]+""")

    /** Null when there's nothing searchable, e.g. only punctuation. */
    fun toFts(input: String): String? =
        word.findAll(input.lowercase()).map { "${it.value}*" }.toList().takeIf { it.isNotEmpty() }?.joinToString(" ")
}
