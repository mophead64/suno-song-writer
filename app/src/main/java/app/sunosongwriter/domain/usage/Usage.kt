package app.sunosongwriter.domain.usage

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Where a request's cost comes from (spec 5.17). */
enum class CostSource(val label: String) {
    /** The provider said what it cost (OpenRouter). */
    REPORTED("reported"),

    /** Worked out from the price table. */
    ESTIMATED("estimated"),
    FREE("free"),

    /** A paid model with no reported cost and no price in the table. */
    UNKNOWN("unknown"),
}

/** One model request's usage. Kept after the song or session is deleted. */
data class UsageRecord(
    val id: Long,
    val backendId: Long,
    val backendName: String,
    val modelId: String,
    val songId: Long?,
    val sessionId: Long?,
    val inputTokens: Int,
    val outputTokens: Int,
    /** Null when [source] is [CostSource.UNKNOWN]. */
    val costUsd: Double?,
    val source: CostSource,
    val at: Long,
)

/** A model's price in US dollars per million tokens, for estimating costs the provider doesn't report. */
data class Price(val backendId: Long, val modelId: String, val inputPerMTok: Double, val outputPerMTok: Double) {
    fun cost(inputTokens: Int, outputTokens: Int): Double = (inputTokens * inputPerMTok + outputTokens * outputPerMTok) / 1_000_000
}

object CostEstimate {
    /** Typical step sizes, for a cost range before a run: a short prompt and reply, up to Finalise with the guide. */
    private const val LOW_IN = 1_500
    private const val LOW_OUT = 400
    private const val HIGH_IN = 5_000
    private const val HIGH_OUT = 1_200

    /** What [requests] requests might cost at [price], low to high. */
    fun range(requests: Int, price: Price): ClosedFloatingPointRange<Double> =
        requests * price.cost(LOW_IN, LOW_OUT)..requests * price.cost(HIGH_IN, HIGH_OUT)

    /** The cost and its source: reported beats free beats the price table. */
    fun of(reportedUsd: Double?, isFree: Boolean, price: Price?, inputTokens: Int, outputTokens: Int): Pair<Double?, CostSource> = when {
        reportedUsd != null -> reportedUsd to CostSource.REPORTED
        isFree -> 0.0 to CostSource.FREE
        price != null -> price.cost(inputTokens, outputTokens) to CostSource.ESTIMATED
        else -> null to CostSource.UNKNOWN
    }
}

data class UsageTotals(
    val requests: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    /** Reported plus estimated cost. */
    val costUsd: Double = 0.0,
    /** The estimated part of [costUsd]. */
    val estimatedUsd: Double = 0.0,
    /** Paid requests whose cost isn't known, so [costUsd] is too low by their amount. */
    val unknownCost: Int = 0,
) {
    val tokens: Long get() = inputTokens + outputTokens

    operator fun plus(r: UsageRecord) = UsageTotals(
        requests = requests + 1,
        inputTokens = inputTokens + r.inputTokens,
        outputTokens = outputTokens + r.outputTokens,
        costUsd = costUsd + (r.costUsd ?: 0.0),
        estimatedUsd = estimatedUsd + if (r.source == CostSource.ESTIMATED) r.costUsd ?: 0.0 else 0.0,
        unknownCost = unknownCost + if (r.source == CostSource.UNKNOWN) 1 else 0,
    )

    companion object {
        fun of(records: Iterable<UsageRecord>) = records.fold(UsageTotals()) { t, r -> t + r }
    }
}

/** One day or month of usage, split by backend. */
data class PeriodUsage(val start: LocalDate, val totals: UsageTotals, val byBackend: List<Pair<String, UsageTotals>>)

object UsageSummary {
    /** The last [count] days, newest first, including days with no requests. */
    fun byDay(records: List<UsageRecord>, today: LocalDate, zone: ZoneId, count: Int = 14): List<PeriodUsage> {
        val byDate = records.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
        return (0 until count).map { i -> today.minusDays(i.toLong()).let { period(it, byDate[it].orEmpty()) } }
    }

    /** The last [count] months, newest first. */
    fun byMonth(records: List<UsageRecord>, today: LocalDate, zone: ZoneId, count: Int = 12): List<PeriodUsage> {
        val byMonth = records.groupBy { YearMonth.from(Instant.ofEpochMilli(it.at).atZone(zone)) }
        val now = YearMonth.from(today)
        return (0 until count).map { i -> now.minusMonths(i.toLong()).let { period(it.atDay(1), byMonth[it].orEmpty()) } }
    }

    /** Usage per song id (null: not for a song, e.g. the Playground), most tokens first. */
    fun bySong(records: List<UsageRecord>): List<Pair<Long?, UsageTotals>> =
        records.groupBy { it.songId }.map { (id, rs) -> id to UsageTotals.of(rs) }.sortedByDescending { it.second.tokens }

    private fun period(start: LocalDate, records: List<UsageRecord>) = PeriodUsage(
        start,
        UsageTotals.of(records),
        records.groupBy { it.backendName }.map { (name, rs) -> name to UsageTotals.of(rs) }.sortedByDescending { it.second.tokens },
    )

    /** Midnight on the first of this month, local time: the monthly limit's window. */
    fun monthStartMillis(nowMillis: Long, zone: ZoneId): Long =
        YearMonth.from(Instant.ofEpochMilli(nowMillis).atZone(zone)).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

/** Spend limits for paid models (spec 5.17). Null: no limit. */
data class SpendLimits(val monthlyUsd: Double? = null, val sessionUsd: Double? = null, val warnPercent: Int = 80) {
    val isSet: Boolean get() = monthlyUsd != null || sessionUsd != null
}

enum class SpendLevel { OK, WARN, REACHED }

object SpendCheck {
    fun level(spentUsd: Double, limitUsd: Double?, warnPercent: Int): SpendLevel = when {
        limitUsd == null -> SpendLevel.OK
        spentUsd >= limitUsd -> SpendLevel.REACHED
        spentUsd >= limitUsd * warnPercent / 100.0 -> SpendLevel.WARN
        else -> SpendLevel.OK
    }

    /** "$0.42", "$0.0031" for small amounts, "$12.50". */
    fun format(usd: Double): String = when {
        usd == 0.0 -> "\$0"
        usd < 0.01 -> "\$%.4f".format(usd)
        else -> "\$%.2f".format(usd)
    }
}
