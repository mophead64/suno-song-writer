package app.sunosongwriter.data.llm

import okhttp3.Headers
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Rate-limit details read from response headers (spec 5.2).
 *
 * Providers disagree on names and formats, so this understands:
 * - `retry-after`: seconds, or an HTTP date
 * - `x-ratelimit-{limit,remaining,reset}-requests` (Groq and others; reset like `2m59.56s`).
 *   What window these count depends on the provider: per day for Groq, per minute for OpenAI.
 *   [app.sunosongwriter.domain.model.Backend.dailyRemainingHeader] says which.
 * - `x-ratelimit-reset` (OpenRouter, only on 429s; epoch milliseconds), used as a retry hint.
 */
data class RateLimitInfo(
    val retryAfterSeconds: Double? = null,
    val limitRequests: Int? = null,
    val remainingRequests: Int? = null,
    val resetRequestsSeconds: Double? = null,
    val resetSeconds: Double? = null,
) {
    val isEmpty: Boolean
        get() = retryAfterSeconds == null && limitRequests == null &&
            remainingRequests == null && resetRequestsSeconds == null && resetSeconds == null

    companion object {
        fun parse(headers: Headers, nowMillis: Long): RateLimitInfo? {
            val info = RateLimitInfo(
                retryAfterSeconds = headers["retry-after"]?.let { parseRetryAfter(it, nowMillis) },
                limitRequests = headers["x-ratelimit-limit-requests"]?.trim()?.toIntOrNull(),
                remainingRequests = headers["x-ratelimit-remaining-requests"]?.trim()?.toIntOrNull(),
                resetRequestsSeconds = headers["x-ratelimit-reset-requests"]?.let { parseReset(it, nowMillis) },
                resetSeconds = headers["x-ratelimit-reset"]?.let { parseReset(it, nowMillis) },
            )
            return info.takeUnless { it.isEmpty }
        }

        fun parseRetryAfter(value: String, nowMillis: Long): Double? {
            value.trim().toDoubleOrNull()?.let { return it.coerceAtLeast(0.0) }
            return try {
                val at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                ((at.toInstant().toEpochMilli() - nowMillis) / 1000.0).coerceAtLeast(0.0)
            } catch (_: DateTimeParseException) {
                null
            }
        }

        /** A duration (`2m59.56s`, `7.66s`, `250ms`, `1h2m`), or an epoch timestamp in seconds or milliseconds. */
        fun parseReset(value: String, nowMillis: Long): Double? {
            val v = value.trim()
            v.toDoubleOrNull()?.let { number ->
                return when {
                    number > 1e12 -> ((number - nowMillis) / 1000.0).coerceAtLeast(0.0)
                    number > 1e9 -> (number - nowMillis / 1000.0).coerceAtLeast(0.0)
                    else -> number
                }
            }
            return parseDuration(v)
        }

        private val durationPart = Regex("""(\d+(?:\.\d+)?)(ms|h|m|s)""")

        fun parseDuration(value: String): Double? {
            val parts = durationPart.findAll(value).toList()
            if (parts.isEmpty() || parts.joinToString("") { it.value } != value) return null
            return parts.sumOf { match ->
                val amount = match.groupValues[1].toDouble()
                when (match.groupValues[2]) {
                    "h" -> amount * 3600
                    "m" -> amount * 60
                    "s" -> amount
                    else -> amount / 1000
                }
            }
        }
    }
}
