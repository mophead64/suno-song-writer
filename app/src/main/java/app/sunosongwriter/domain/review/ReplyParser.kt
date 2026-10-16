package app.sunosongwriter.domain.review

import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StylePrompt
import app.sunosongwriter.domain.model.SunoSettings
import app.sunosongwriter.domain.prompt.SessionAction

/** What a reply changed, applied over the version it was based on. [title] and [tags] come from Quick start replies. */
data class ParsedContent(
    val content: SongContent,
    val note: String?,
    val changedParts: Set<Part>,
    val title: String? = null,
    val tags: List<String> = emptyList(),
) {
    enum class Part { STYLE, LYRICS, SETTINGS }
}

/**
 * Reads replies to actions that produce a version (spec 5.4). Expects `STYLE:` / `LYRICS:` /
 * `SETTINGS:` / `NOTE:` headings, but also accepts Markdown headings (`## Lyrics`, `**Style:**`),
 * code fences, and, when a reply has no headings at all, treats the whole reply as the one part
 * the action asked for (lyrics for Write lyrics, style for Write style).
 */
object ReplyParser {
    private enum class Heading { STYLE, LYRICS, SETTINGS, NOTE, TITLE, TAGS }

    private val headingLine = Regex(
        // The heading word must be followed by a colon or the end of the line, so "Style Influence: 90%"
        // or a lyric like "Notes on the breeze" isn't mistaken for a heading.
        """^\s*(?:#{1,4}\s*)?\**\s*(style(?: prompt)?|lyrics|settings|sliders|suno settings|note|change note|notes|title|song title|tags|genre tags|genres)\s*\**\s*(?::\s*\**\s*(.*)|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(reply: String, base: SongContent, action: SessionAction): ParsedContent? {
        val parts = split(reply)
        val style = parts[Heading.STYLE]?.let(::clean)?.takeIf { it.isNotBlank() }
        val lyrics = parts[Heading.LYRICS]?.let(::clean)?.takeIf { it.isNotBlank() }
        val settings = parts[Heading.SETTINGS]?.let { parseSettings(it, base.settings) }
        val note = parts[Heading.NOTE]?.let(::clean)?.lines()?.firstOrNull { it.isNotBlank() }?.trim()
        val title = parts[Heading.TITLE]?.let(::clean)?.lines()?.firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '\u201c', '\u201d', '*')?.takeIf { it.isNotBlank() }
        val tags = parts[Heading.TAGS]?.let(::clean)?.split(',', '\n')?.map { it.trim().trimStart('-', '*', '#', ' ').trim() }?.filter { it.isNotBlank() && it.length <= 40 }.orEmpty()

        var content = base
        val changed = mutableSetOf<ParsedContent.Part>()
        if (style != null) {
            content = content.copy(style = StylePrompt.fromFreeText(style))
            changed += ParsedContent.Part.STYLE
        }
        if (lyrics != null) {
            content = content.copy(lyrics = lyrics)
            changed += ParsedContent.Part.LYRICS
        }
        if (settings != null && settings != base.settings) {
            content = content.copy(settings = settings)
            changed += ParsedContent.Part.SETTINGS
        }
        if (changed.isEmpty() && parts.isEmpty()) {
            // No headings at all: the whole reply is the part this action asked for.
            val whole = clean(reply).takeIf { it.isNotBlank() } ?: return null
            when (action) {
                SessionAction.WRITE_LYRICS -> { content = content.copy(lyrics = whole); changed += ParsedContent.Part.LYRICS }
                SessionAction.WRITE_STYLE -> { content = content.copy(style = StylePrompt.fromFreeText(whole)); changed += ParsedContent.Part.STYLE }
                else -> return null
            }
        }
        if (changed.isEmpty()) return null
        return ParsedContent(content, note, changed, title, tags.distinctBy { it.lowercase() }.take(6))
    }

    private fun split(reply: String): Map<Heading, String> {
        val result = linkedMapOf<Heading, StringBuilder>()
        var current: Heading? = null
        for (line in reply.lines()) {
            val match = headingLine.find(line)
            val heading = match?.groupValues?.get(1)?.lowercase()?.let { word ->
                when {
                    word.endsWith("title") -> Heading.TITLE
                    word == "tags" || word == "genre tags" || word == "genres" -> Heading.TAGS
                    word.startsWith("style") -> Heading.STYLE
                    word == "lyrics" -> Heading.LYRICS
                    word.contains("settings") || word == "sliders" -> Heading.SETTINGS
                    else -> Heading.NOTE
                }
            }
            if (heading != null) {
                current = heading
                val inline = match.groupValues[2].trim().trim('*').trim()
                val sb = result.getOrPut(heading) { StringBuilder() }
                if (inline.isNotEmpty()) sb.appendLine(inline)
            } else if (current != null) {
                result.getValue(current).appendLine(line)
            }
        }
        return result.mapValues { it.value.toString() }
    }

    /** Drops code fences and surrounding blank lines. */
    private fun clean(text: String): String =
        text.lines().filterNot { it.trim().startsWith("```") }.joinToString("\n").trim()

    private val settingLine = Regex("""^\s*[-*]?\s*(model|weirdness|style influence|variety)\s*[:=]\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)

    fun parseSettings(text: String, base: SunoSettings): SunoSettings {
        var s = base
        for (line in text.lines()) {
            val m = settingLine.find(line) ?: continue
            val value = m.groupValues[2].trim().trimEnd('.')
            val number = Regex("""\d{1,3}""").find(value)?.value?.toIntOrNull()?.coerceIn(0, 100)
            s = when (m.groupValues[1].lowercase()) {
                "model" -> s.copy(model = value.takeIf { it.isNotBlank() })
                "weirdness" -> number?.let { s.copy(weirdness = it) } ?: s
                "style influence" -> number?.let { s.copy(styleInfluence = it) } ?: s
                else -> number?.let { s.copy(variety = it) } ?: s
            }
        }
        return s
    }
}
