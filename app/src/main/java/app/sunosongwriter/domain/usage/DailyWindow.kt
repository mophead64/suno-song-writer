package app.sunosongwriter.domain.usage

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Calendar days in a provider's reset time zone (e.g. Gemini resets at midnight Pacific). */
object DailyWindow {
    fun zone(id: String): ZoneId = try {
        ZoneId.of(id)
    } catch (_: DateTimeException) {
        ZoneOffset.UTC
    }

    fun dayKey(zoneId: String, nowMillis: Long): String =
        Instant.ofEpochMilli(nowMillis).atZone(zone(zoneId)).toLocalDate().toString()

    fun nextReset(zoneId: String, nowMillis: Long): Long {
        val zone = zone(zoneId)
        return Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
