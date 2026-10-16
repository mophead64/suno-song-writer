package app.sunosongwriter.domain.lint

import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StylePrompt
import app.sunosongwriter.domain.model.StyleSection
import kotlinx.serialization.Serializable

/**
 * Suno's box limits (spec 5.9). Editable, because Suno changes them. Defaults checked 2026-10-16:
 * v6 allows 1,000 style characters and 5,000 lyric characters.
 */
@Serializable
data class SunoLimits(
    val styleChars: Int = 1_000,
    val lyricsChars: Int = 5_000,
    /** Bracket tags longer than this many words get a warning: Suno may ignore or misread them. */
    val tagWords: Int = 6,
)

enum class LintLevel { WARNING, INFO }

data class LintIssue(val rule: String, val level: LintLevel, val message: String)

/**
 * Deterministic checks from the Suno v6 guide (spec 5.10). No model involved. Each rule is a small
 * function so it can be tested on its own. Lint never blocks copying; it only warns.
 */
object SunoLint {
    fun check(content: SongContent, limits: SunoLimits = SunoLimits()): List<LintIssue> =
        listOfNotNull(
            styleOrder(content.style),
            missingCoreSections(content.style),
            endingInstruction(content.style),
            styleLength(content.style, limits),
            lyricsLength(content.lyrics, limits),
            instrumentalBuffer(content.lyrics),
            linesAfterEnd(content.lyrics),
            intro(content.lyrics),
            lyricsInBrackets(content.lyrics),
            longTags(content.lyrics, limits),
            sungDirections(content.lyrics),
            weirdness(content.settings.weirdness),
        )

    // Lyrics structure

    private val tag = Regex("""^\s*\[([^\]]+)]\s*$""")

    /** Lyric lines and section tags, ignoring blank lines. */
    private fun lines(lyrics: String): List<String> = lyrics.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun tagOf(line: String): String? = tag.find(line)?.groupValues?.get(1)?.trim()

    private fun isEndTag(t: String) = t.lowercase().let { "hard stop" in it || it == "end" || it == "the end" }

    /** A line that will be sung: not a tag, and not only a parenthetical backing vocal. */
    private fun isVocal(line: String) = tagOf(line) == null

    fun instrumentalBuffer(lyrics: String): LintIssue? {
        val all = lines(lyrics)
        if (all.none(::isVocal)) return null
        val afterLastVocal = all.drop(all.indexOfLast(::isVocal) + 1).mapNotNull(::tagOf).map { it.lowercase() }
        val hasBuffer = afterLastVocal.any { "instrumental" in it || ("outro" in it && "vocal" !in it) }
        val hasStop = afterLastVocal.any(::isEndTag)
        if (hasBuffer && hasStop) return null
        val missing = listOfNotNull("[Instrumental Outro]".takeIf { !hasBuffer }, "[Hard Stop] and [End]".takeIf { !hasStop })
        return LintIssue(
            "instrumental-buffer", LintLevel.WARNING,
            "After the last sung line, add ${missing.joinToString(" then ")}. Without somewhere to land, Suno can cut off the final lyric or drag the outro on.",
        )
    }

    fun linesAfterEnd(lyrics: String): LintIssue? {
        val all = lines(lyrics)
        val end = all.indexOfFirst { line -> tagOf(line)?.let(::isEndTag) == true }
        if (end < 0) return null
        val stray = all.drop(end + 1).filter(::isVocal)
        if (stray.isEmpty()) return null
        return LintIssue("lines-after-end", LintLevel.WARNING, "${stray.size} lyric line(s) come after [${tagOf(all[end])}], starting \"${stray.first().take(40)}\". Suno is unlikely to sing them.")
    }

    fun intro(lyrics: String): LintIssue? {
        val all = lines(lyrics)
        val first = all.firstOrNull() ?: return null
        val firstTag = tagOf(first)?.lowercase() ?: return null // lyrics start immediately
        if ("intro" !in firstTag || "instrumental" in firstTag) return null
        val next = all.getOrNull(1)
        if (next != null && isVocal(next)) return null // [Intro] then the first lyric: vocals start straight away
        return LintIssue(
            "intro", LintLevel.WARNING,
            "The intro is undefined: [${tagOf(first)}] isn't followed by a lyric. Use [Instrumental Intro], or start singing right after [Intro], so Suno doesn't improvise noises.",
        )
    }

    private val sectionWords = listOf(
        "intro", "verse", "chorus", "hook", "refrain", "bridge", "breakdown", "solo", "outro", "interlude",
        "drop", "build", "break", "end", "stop", "instrumental", "final", "spoken", "rap", "tag", "coda", "vamp",
    )

    /** Bracketed text isn't sung, so lyric lines written as [tags] would be skipped (seen with small local models). */
    fun lyricsInBrackets(lyrics: String): LintIssue? {
        val all = lines(lyrics)
        val lyricLike = all.mapNotNull(::tagOf).filter { t ->
            val lower = t.lowercase()
            sectionWords.none { it in lower } && t.split(Regex("""\s+""")).size >= 3
        }
        val noVocals = all.isNotEmpty() && all.none(::isVocal)
        if (lyricLike.size < 2 && !noVocals) return null
        val example = lyricLike.firstOrNull()?.let { ", e.g. [${it.take(40)}]" }.orEmpty()
        val what = if (noVocals) "Every line is in [brackets], so nothing will be sung$example."
        else "${lyricLike.size} lines in [brackets] look like lyrics or directions$example; Suno doesn't sing bracketed text."
        return LintIssue("lyrics-in-brackets", LintLevel.WARNING, "$what Keep only section names in brackets.")
    }

    fun longTags(lyrics: String, limits: SunoLimits): LintIssue? {
        val long = lines(lyrics).mapNotNull(::tagOf).filter { it.split(Regex("""\s+""")).size > limits.tagWords }
        if (long.isEmpty()) return null
        return LintIssue("long-tags", LintLevel.WARNING, "${long.size} bracket tag(s) are longer than ${limits.tagWords} words, e.g. [${long.first().take(50)}]. Keep tags short; put musical detail in the style prompt.")
    }

    private val direction = Regex("""\(([^)]*)\)""")
    private val directionWords = listOf("vocals:", "vocal:", "fade", "whisper", "spoken", "instrumental", "guitar", "drums", "piano", "beat", "tempo", "softly", "build", "pause")

    /** Parentheses are sung as backing vocals, so directions in them get sung. */
    fun sungDirections(lyrics: String): LintIssue? {
        val suspects = lines(lyrics).filter { tagOf(it) == null }.flatMap { line ->
            direction.findAll(line).map { it.groupValues[1] }.filter { inner -> ':' in inner || directionWords.any { inner.lowercase().contains(it) } }.toList()
        }
        if (suspects.isEmpty()) return null
        return LintIssue("sung-directions", LintLevel.WARNING, "${suspects.size} parenthetical(s) look like directions, e.g. \"(${suspects.first().take(40)})\". Suno sings anything in parentheses; move directions to the style prompt.")
    }

    // Style prompt

    fun styleOrder(style: StylePrompt): LintIssue? {
        val text = style.freeText ?: return null // rendered sections are always in order
        val order = text.lines().mapNotNull { line -> StylePrompt.parse(line).keys.firstOrNull { it != StyleSection.NOTES } }
        val outOfOrder = order.zipWithNext().firstOrNull { (a, b) -> b.ordinal < a.ordinal } ?: return null
        return LintIssue(
            "style-order", LintLevel.WARNING,
            "${outOfOrder.second.label} comes after ${outOfOrder.first.label}. The guide's order is Genre, Vocals, Drums, Guitars, Bass, Other instruments, Arrangement, Production, Ending.",
        )
    }

    fun missingCoreSections(style: StylePrompt): LintIssue? {
        if (style.isEmpty) return LintIssue("style-empty", LintLevel.WARNING, "There's no style prompt yet.")
        val missing = listOf(StyleSection.VOCALS, StyleSection.DRUMS, StyleSection.PRODUCTION).filter { style[it].isBlank() }
        if (missing.isEmpty()) return null
        return LintIssue("core-sections", LintLevel.WARNING, "No ${missing.joinToString(" or ") { it.label.lowercase() }} section. Describing these is what stops distant vocals, weak drums and muddy mixes.")
    }

    private val endingWords = listOf("hard stop", "complete every", "instrumental outro", "final hard", "clean ending", "ends on")

    fun endingInstruction(style: StylePrompt): LintIssue? {
        if (style.isEmpty) return null
        val flat = style.flatText().lowercase()
        if (style[StyleSection.ENDING].isNotBlank() || endingWords.any { it in flat }) return null
        return LintIssue("ending", LintLevel.WARNING, "The style has no ending instruction. The guide suggests \"complete every written lyric before a short instrumental outro and final hard stop\".")
    }

    fun styleLength(style: StylePrompt, limits: SunoLimits): LintIssue? {
        val n = style.flatText().length
        return if (n > limits.styleChars) LintIssue("style-length", LintLevel.WARNING, "The style prompt is $n characters; Suno allows ${limits.styleChars}.") else null
    }

    fun lyricsLength(lyrics: String, limits: SunoLimits): LintIssue? {
        val n = lyrics.trim().length
        return when {
            n == 0 -> LintIssue("lyrics-empty", LintLevel.WARNING, "There are no lyrics yet.")
            n > limits.lyricsChars -> LintIssue("lyrics-length", LintLevel.WARNING, "The lyrics are $n characters; Suno allows ${limits.lyricsChars}.")
            else -> null
        }
    }

    // Settings

    fun weirdness(value: Int?): LintIssue? {
        if (value == null || value in 30..50) return null
        val note = if (value < 30) "cleaner and more controlled" else "more experimental"
        return LintIssue("weirdness", LintLevel.INFO, "Weirdness $value% is outside the guide's 30–50% sweet spot: expect $note results.")
    }

    /** The exact text for Suno's lyrics box: Unix line endings, no trailing spaces, at most one blank line in a row. */
    fun cleanLyrics(lyrics: String): String =
        lyrics.replace("\r\n", "\n").replace('\r', '\n').lines().joinToString("\n") { it.trimEnd() }
            .replace(Regex("""\n{3,}"""), "\n\n").trim()

    /** The exact text for Suno's style box. */
    fun cleanStyle(style: StylePrompt): String =
        style.flatText().replace("\r\n", "\n").lines().joinToString("\n") { it.trimEnd() }.trim()
}
