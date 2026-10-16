package app.sunosongwriter.domain.review

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class Verdict { APPROVE, CHANGES }

enum class SuggestionTarget(val label: String) {
    LYRICS("Lyrics"),
    STYLE("Style"),
    SETTINGS("Settings"),
    OTHER("General"),
    ;

    companion object {
        fun from(text: String?): SuggestionTarget {
            val t = text?.lowercase().orEmpty()
            return when {
                "lyric" in t -> LYRICS
                "style" in t || "genre" in t || "production" in t || "vocal" in t -> STYLE
                "setting" in t || "slider" in t || "weird" in t || "influence" in t -> SETTINGS
                else -> OTHER
            }
        }
    }
}

data class ParsedSuggestion(
    val target: SuggestionTarget,
    val section: String?,
    val change: String,
    val rationale: String?,
)

data class ParsedReview(val verdict: Verdict, val summary: String?, val suggestions: List<ParsedSuggestion>)

/**
 * Reads review replies (spec 5.6). Asks for JSON but tolerates what models actually send:
 * code fences, text around the object, trailing commas, unquoted keys, and alternative field
 * names ("changes", "items", "suggestion", "reason"...). Returns null when there's no usable JSON,
 * so the caller can retry or fall back to showing the raw reply.
 */
@OptIn(ExperimentalSerializationApi::class)
object ReviewParser {
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    fun parse(reply: String): ParsedReview? {
        val obj = extractObject(reply) ?: return null
        val list = listOf("suggestions", "changes", "items", "edits", "recommendations")
            .firstNotNullOfOrNull { obj[it] as? JsonArray }
            ?: JsonArray(emptyList())
        val suggestions = list.mapNotNull { item ->
            when (item) {
                is JsonObject -> {
                    val change = item.str("change", "suggestion", "text", "edit", "description", "fix") ?: return@mapNotNull null
                    ParsedSuggestion(
                        target = SuggestionTarget.from(item.str("target", "area", "part", "type")),
                        section = item.str("section", "where", "location", "field")?.takeIf { it.isNotBlank() },
                        change = change.trim(),
                        rationale = item.str("rationale", "reason", "why", "because")?.trim()?.takeIf { it.isNotBlank() },
                    )
                }
                is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }?.let { ParsedSuggestion(SuggestionTarget.OTHER, null, it.trim(), null) }
                else -> null
            }
        }
        val verdictText = obj.str("verdict", "decision", "status")?.lowercase()
        val verdict = when {
            verdictText == null -> if (suggestions.isEmpty()) Verdict.APPROVE else Verdict.CHANGES
            listOf("approve", "approved", "lgtm", "ok", "ready", "accept").any { it in verdictText } && suggestions.isEmpty() -> Verdict.APPROVE
            listOf("approve", "approved", "lgtm", "ready").any { verdictText.startsWith(it) } -> Verdict.APPROVE
            else -> Verdict.CHANGES
        }
        // Without a verdict or suggestions this is just some other JSON, not a review.
        if (verdictText == null && suggestions.isEmpty() && obj["summary"] == null) return null
        return ParsedReview(verdict, obj.str("summary", "overall", "comment")?.trim(), suggestions)
    }

    private fun extractObject(reply: String): JsonObject? {
        val text = reply.lines().filterNot { it.trim().startsWith("```") }.joinToString("\n")
        val start = text.indexOf('{')
        if (start < 0) return null
        // Try the widest span first, then shrink to earlier closing braces.
        var end = text.lastIndexOf('}')
        while (end > start) {
            val candidate = text.substring(start, end + 1)
            try {
                return json.parseToJsonElement(candidate) as? JsonObject
            } catch (_: IllegalArgumentException) {
                end = text.lastIndexOf('}', end - 1)
            }
        }
        return null
    }

    private fun JsonObject.str(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        when (val v: JsonElement? = this[key]) {
            is JsonPrimitive -> v.contentOrNull
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("; ").ifBlank { null }
            else -> null
        }
    }
}
