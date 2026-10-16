package app.sunosongwriter.domain.prompt

/** What a step asks a participant to do (spec 5.4). */
enum class SessionAction(
    val label: String,
    /** Replies are parsed into a new version; otherwise (review) into suggestions. */
    val producesVersion: Boolean,
    /** The run dialog asks for instructions, and requires them. */
    val needsInstructions: Boolean = false,
    /** Shown in the run dialog. Fix starts from a sent snapshot's results screen instead. */
    val runnable: Boolean = true,
) {
    WRITE_LYRICS("Write lyrics", producesVersion = true),
    WRITE_STYLE("Write style", producesVersion = true),
    REVIEW("Review", producesVersion = false),
    IMPLEMENT("Implement", producesVersion = true),
    TWEAK("Tweak", producesVersion = true, needsInstructions = true),
    FINALISE("Finalise", producesVersion = true),
    FIX("Fix from results", producesVersion = true, runnable = false),
    /** Idea → a whole first draft, plus a title and genre tags when the song has none. */
    QUICK_START("Quick start", producesVersion = true),
    /** A nominated participant decides for the Writers' Room (spec 5.5, auto chair). */
    CHAIR("Chair decision", producesVersion = false, runnable = false),
}

/**
 * Identifies a template: `builtin:<name>` for the read-only built-ins in [BuiltInTemplates],
 * `user:<id>` for templates the user made (spec 5.8).
 */
@JvmInline
value class TemplateKey(val value: String) {
    val isBuiltIn: Boolean get() = value.startsWith(BUILTIN)
    val userId: Long? get() = value.removePrefix(USER).takeIf { value.startsWith(USER) }?.toLongOrNull()

    companion object {
        private const val BUILTIN = "builtin:"
        private const val USER = "user:"
        fun builtIn(name: String) = TemplateKey(BUILTIN + name)
        fun user(id: Long) = TemplateKey(USER + id)
    }
}

data class PromptTemplate(
    val key: TemplateKey,
    val action: SessionAction,
    val name: String,
    val body: String,
    /** For a user copy, the template it was copied from. */
    val basedOn: TemplateKey? = null,
) {
    val isBuiltIn: Boolean get() = key.isBuiltIn
}

/** Values for a template's `{{variables}}`. */
data class TemplateContext(
    val title: String = "",
    val artist: String = "",
    val tags: String = "",
    val concept: String = "",
    val style: String = "",
    val lyrics: String = "",
    val sliders: String = "",
    val suggestions: String = "",
    val results: String = "",
    val guide: String = "",
    val instructions: String = "",
) {
    fun asMap(): Map<String, String> = mapOf(
        "title" to title,
        "artist" to artist,
        "tags" to tags,
        "concept" to concept,
        "style" to style,
        "lyrics" to lyrics,
        "sliders" to sliders,
        "suggestions" to suggestions,
        "results" to results,
        "guide" to guide,
        "instructions" to instructions,
    )

    companion object {
        /** For the template editor's cheat sheet. */
        val VARIABLES: List<Pair<String, String>> = listOf(
            "title" to "Song title",
            "artist" to "Artist",
            "tags" to "Genre tags",
            "concept" to "The song's concept",
            "style" to "Current style prompt",
            "lyrics" to "Current lyrics",
            "sliders" to "Current Suno settings",
            "suggestions" to "Accepted suggestions (Implement), or the choices to decide on (Chair decision)",
            "results" to "Logged Suno takes, issues, notes and their fixes (Fix from results)",
            "guide" to "The Suno v6 prompting guide",
            "instructions" to "What you typed when running the step",
        )
    }
}

object TemplateRenderer {
    private val variable = Regex("""\{\{\s*([a-zA-Z_]+)\s*\}\}""")

    /** Replaces `{{name}}` (spaces allowed inside the braces). Unknown names are left untouched. Empty values become "(none)". */
    fun render(body: String, context: TemplateContext): String {
        val values = context.asMap()
        return variable.replace(body) { m ->
            val name = m.groupValues[1].lowercase()
            if (name in values) values.getValue(name).ifBlank { "(none)" } else m.value
        }
    }

    fun unknownVariables(body: String): List<String> {
        val known = TemplateContext().asMap().keys
        return variable.findAll(body).map { it.groupValues[1].lowercase() }.filter { it !in known }.distinct().toList()
    }
}
