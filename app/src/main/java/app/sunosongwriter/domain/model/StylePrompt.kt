package app.sunosongwriter.domain.model

import kotlinx.serialization.Serializable

/**
 * The Style box sections, in the order the Suno v6 guide recommends (spec 4):
 * Genre → Vocals → Drums → Guitars → Bass → Other Instruments → Arrangement/Energy → Production → Ending.
 * [NOTES] holds lines that don't belong to a labelled section, and is written without a label.
 */
@Serializable
enum class StyleSection(val label: String, val wireLabel: String?, val aliases: List<String>) {
    GENRE("Genre", "Genre", listOf("genre", "genres", "genre(s)")),
    VOCALS("Vocals", "Vocals", listOf("vocals", "vocal")),
    DRUMS("Drums", "Drums", listOf("drums", "drum", "percussion")),
    GUITARS("Guitars", "Guitars", listOf("guitars", "guitar")),
    BASS("Bass", "Bass", listOf("bass")),
    OTHER_INSTRUMENTS("Other instruments", "Other Instruments", listOf("other instruments", "other instrument", "instruments", "synths")),
    ARRANGEMENT("Arrangement / energy", "Arrangement", listOf("arrangement", "energy", "arrangement/energy", "arrangement / energy")),
    PRODUCTION("Production", "Production", listOf("production", "production quality", "mix")),
    ENDING("Ending", "Ending", listOf("ending", "ending instruction", "ending instructions", "outro")),
    NOTES("Other notes", null, emptyList()),
    ;

    companion object {
        /** Unlabelled notes usually lead a hand-written prompt, so they're written first. */
        val renderOrder: List<StyleSection> = listOf(NOTES) + entries.filter { it != NOTES }
    }
}

/**
 * A style prompt stored as named sections, so diffs and lint can work per section (spec 4).
 *
 * [freeText] is set when the user edited the prompt as one block; it is then the exact text sent to Suno,
 * and [sections] holds a best-effort parse of it for diffs.
 */
@Serializable
data class StylePrompt(
    val sections: Map<StyleSection, String> = emptyMap(),
    val freeText: String? = null,
) {
    operator fun get(section: StyleSection): String = sections[section].orEmpty()

    fun with(section: StyleSection, text: String): StylePrompt =
        copy(sections = (sections + (section to text)).filterValues { it.isNotBlank() }, freeText = null)

    /** The text for Suno's Style box. */
    fun flatText(): String = freeText ?: render(sections)

    val isEmpty: Boolean get() = flatText().isBlank()

    companion object {
        fun render(sections: Map<StyleSection, String>): String =
            StyleSection.renderOrder.mapNotNull { section ->
                val text = sections[section]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                section.wireLabel?.let { "$it: $text" } ?: text
            }.joinToString("\n")

        private val bullet = Regex("""^(?:[-*•]|\d{1,2}[.)])\s+""")

        /** Some models write each section as a Suno-style tag: "[Vocals: gritty]". */
        private fun unwrapBrackets(line: String): String =
            if (line.startsWith("[") && line.endsWith("]") && ':' in line) line.substring(1, line.length - 1).trim() else line

        private val labelPattern = Regex("""^\s*([A-Za-z][A-Za-z ()/]*?)\s*:\s*(.*)$""")

        /**
         * Best-effort split of free text into sections. Lines starting with a known label ("Vocals: …")
         * start that section; following unlabelled lines continue it; anything before the first label
         * goes to [StyleSection.NOTES].
         */
        fun parse(text: String): Map<StyleSection, String> {
            val result = linkedMapOf<StyleSection, MutableList<String>>()
            var current = StyleSection.NOTES
            for (raw in text.lines()) {
                // Tolerate Markdown from chat apps: bold labels and bullet / numbered list markers.
                val line = raw.replace("**", "").trim().replace(bullet, "").let(::unwrapBrackets)
                if (line.isEmpty()) continue
                val match = labelPattern.find(line)
                val section = match?.groupValues?.get(1)?.trim()?.lowercase()?.let { label ->
                    StyleSection.entries.firstOrNull { label in it.aliases }
                }
                if (section != null) {
                    current = section
                    match.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let { result.getOrPut(section) { mutableListOf() } += it }
                } else {
                    result.getOrPut(current) { mutableListOf() } += line
                }
            }
            return result.mapValues { (_, lines) -> lines.joinToString(" ") }.filterValues { it.isNotBlank() }
        }

        /** Builds a prompt from text the user typed as one block. */
        fun fromFreeText(text: String): StylePrompt {
            val parsed = parse(text)
            // If the text is exactly what the sections would render to, there's nothing to preserve.
            return if (render(parsed) == text.trim()) StylePrompt(parsed) else StylePrompt(parsed, text.trim().ifEmpty { null })
        }
    }
}
