package app.sunosongwriter.domain.results

/** A problem heard in a Suno take (spec 5.12), and what to change in the prompt to fix it (5.13). */
data class IssueFix(
    /** Stored on takes. A built-in's key (`lyrics-cut-off`), or a custom tag's name. */
    val tag: String,
    val label: String,
    val fixText: String,
    val isBuiltIn: Boolean,
    /** A built-in whose fix text the user changed. */
    val isEdited: Boolean = false,
)

/**
 * The troubleshooting list from the bundled Suno v6 guide (section 14, plus its ending and intro advice),
 * in the spec's order. The fix text is the default; users can edit it in Settings → Issue fixes.
 */
object BuiltInIssues {
    val all: List<IssueFix> = listOf(
        builtIn("muddy-mix", "Muddy mix", "Add to the Production line: open defined low-mids, clear high-end air, crisp transients."),
        builtIn("distant-vocals", "Distant vocals", "Add to the Vocals line: close-mic lead vocals, intimate vocal presence."),
        builtIn("volume-swings", "Volume swings", "Add to the Production line: stable vocal intensity and loudness, consistent mix fidelity throughout."),
        builtIn("instruments-blur", "Instruments blur together", "Add to the Production line: layered instruments with clear separation."),
        builtIn("verses-lose-energy", "Verses lose energy", "Add to the Arrangement line: full-band energy throughout, foreground riffs during verses."),
        builtIn("guitars-disappear", "Guitars disappear", "Add to the Guitars line: continuous guitar activity, layered rhythm and lead guitars throughout."),
        builtIn(
            "weak-drums", "Weak drums",
            "Don't just say \"powerful drums\". On the Drums line, describe what the drummer is doing, e.g. rapid double bass, active fills, ghost notes, ride bell accents, tom fills, blast beats, fast punk rhythm.",
        ),
        builtIn(
            "random-instruments", "Random instruments",
            "Lower Variety, lower Weirdness and raise Style Influence. Be more specific about the instrumentation you actually want.",
        ),
        builtIn(
            "random-intro-noise", "Random intro noise",
            "Don't leave the intro undefined: start the lyrics immediately, or put a short [Instrumental Intro] first and describe it in the style prompt.",
        ),
        builtIn("slow-intro", "Slow intro", "Start the lyrics immediately, or clearly define a short [Instrumental Intro]."),
        builtIn(
            "outro-drags", "Outro drags",
            "End the lyrics with [Instrumental Outro], [Hard Stop] and [End] instead of a vague [Outro], and say on the Ending line how the song should finish.",
        ),
        builtIn(
            "lyrics-cut-off", "Lyrics cut off",
            "Add an instrumental buffer after the final vocal section: [Instrumental Outro], then [Hard Stop] and [End]. Ending line: complete every written lyric before a short instrumental outro and final hard stop.",
        ),
        builtIn(
            "vocals-too-clean", "Vocals too clean",
            "On the Vocals line, describe the texture explicitly, e.g. grit, rasp, fry, false cord growls, chest voice, gutturals.",
        ),
        builtIn("operatic-female-vocals", "Operatic female vocals", "Add to the Vocals line: gritty chest voice, contemporary rock delivery, aggressive edge."),
    )

    private val byTag = all.associateBy { it.tag }

    fun find(tag: String): IssueFix? = byTag[tag]

    private fun builtIn(tag: String, label: String, fix: String) = IssueFix(tag, label, fix, isBuiltIn = true)
}

/** A stored row of the tag → fix table: an edited built-in, or a custom tag. */
data class StoredFix(val tag: String, val fixText: String, val builtIn: Boolean)

object IssueCatalog {
    /** Built-ins in the guide's order (with the user's edits), then custom tags alphabetically. */
    fun merge(stored: List<StoredFix>): List<IssueFix> {
        val overrides = stored.filter { it.builtIn }.associateBy { it.tag }
        val builtIns = BuiltInIssues.all.map { b ->
            overrides[b.tag]?.let { b.copy(fixText = it.fixText, isEdited = it.fixText != b.fixText) } ?: b
        }
        val custom = stored
            .filter { !it.builtIn && BuiltInIssues.find(it.tag) == null }
            .sortedBy { it.tag.lowercase() }
            .map { IssueFix(it.tag, it.tag, it.fixText, isBuiltIn = false) }
        return builtIns + custom
    }

    /** A tag's label, including tags whose custom entry was since deleted. */
    fun label(tag: String, catalog: List<IssueFix>): String = catalog.firstOrNull { it.tag == tag }?.label ?: tag

    /** Normalises a custom tag's name, or null if it's empty or clashes with an existing tag. */
    fun customTagName(input: String, catalog: List<IssueFix>): String? {
        val name = input.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() } ?: return null
        val clash = catalog.any { it.tag.equals(name, ignoreCase = true) || it.label.equals(name, ignoreCase = true) }
        return name.takeUnless { clash }
    }
}
