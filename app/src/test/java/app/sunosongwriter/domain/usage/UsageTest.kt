package app.sunosongwriter.domain.usage

import app.sunosongwriter.data.llm.OpenRouterCredit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class UsageTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(date: String, hour: Int = 12) = LocalDate.parse(date).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun record(at: Long, backend: String = "OpenRouter", songId: Long? = 1, cost: Double? = 0.01, source: CostSource = CostSource.REPORTED, tokens: Int = 100) =
        UsageRecord(0, 1, backend, "m", songId, null, tokens, tokens / 2, cost, source, at)

    @Test
    fun costComesFromTheProviderThenFreeThenThePriceTable() {
        val price = Price(1, "m", inputPerMTok = 3.0, outputPerMTok = 15.0)
        assertEquals(0.5 to CostSource.REPORTED, CostEstimate.of(0.5, isFree = false, price, 1000, 1000))
        assertEquals(0.0 to CostSource.FREE, CostEstimate.of(null, isFree = true, price, 1000, 1000))
        val (estimated, source) = CostEstimate.of(null, isFree = false, price, 1_000_000, 100_000)
        assertEquals(4.5, estimated!!, 1e-9)
        assertEquals(CostSource.ESTIMATED, source)
        assertEquals(null to CostSource.UNKNOWN, CostEstimate.of(null, isFree = false, null, 1000, 1000))
    }

    @Test
    fun aRunsCostRangeScalesWithRequests() {
        val r = CostEstimate.range(10, Price(1, "m", 1.0, 2.0))
        assertEquals(10 * (1_500 * 1.0 + 400 * 2.0) / 1_000_000, r.start, 1e-12)
        assertEquals(10 * (5_000 * 1.0 + 1_200 * 2.0) / 1_000_000, r.endInclusive, 1e-12)
    }

    @Test
    fun totalsSeparateEstimatedAndUnknownCosts() {
        val t = UsageTotals.of(
            listOf(
                record(0, cost = 0.02),
                record(0, cost = 0.01, source = CostSource.ESTIMATED),
                record(0, cost = null, source = CostSource.UNKNOWN),
                record(0, cost = 0.0, source = CostSource.FREE),
            ),
        )
        assertEquals(4, t.requests)
        assertEquals(0.03, t.costUsd, 1e-9)
        assertEquals(0.01, t.estimatedUsd, 1e-9)
        assertEquals(1, t.unknownCost)
        assertEquals(600L, t.tokens)
    }

    @Test
    fun daysAndMonthsIncludeEmptyPeriodsAndSplitByBackend() {
        val records = listOf(record(at("2026-10-16")), record(at("2026-10-16"), backend = "Groq"), record(at("2026-10-14")), record(at("2026-09-02")))
        val days = UsageSummary.byDay(records, LocalDate.parse("2026-10-16"), zone, count = 3)
        assertEquals(listOf("2026-10-16", "2026-10-15", "2026-10-14"), days.map { it.start.toString() })
        assertEquals(listOf(2, 0, 1), days.map { it.totals.requests })
        assertEquals(setOf("OpenRouter", "Groq"), days.first().byBackend.map { it.first }.toSet())

        val months = UsageSummary.byMonth(records, LocalDate.parse("2026-10-16"), zone, count = 2)
        assertEquals(listOf("2026-10-01", "2026-09-01"), months.map { it.start.toString() })
        assertEquals(listOf(3, 1), months.map { it.totals.requests })
    }

    @Test
    fun bySongPutsTheBiggestFirst() {
        val songs = UsageSummary.bySong(listOf(record(0, songId = 1, tokens = 10), record(0, songId = 2, tokens = 1000), record(0, songId = null, tokens = 50)))
        assertEquals(listOf(2L, null, 1L), songs.map { it.first })
    }

    @Test
    fun monthStartIsMidnightOnTheFirst() {
        assertEquals(at("2026-10-01", 0), UsageSummary.monthStartMillis(at("2026-10-16"), zone))
    }

    @Test
    fun spendLevels() {
        assertEquals(SpendLevel.OK, SpendCheck.level(5.0, null, 80))
        assertEquals(SpendLevel.OK, SpendCheck.level(7.9, 10.0, 80))
        assertEquals(SpendLevel.WARN, SpendCheck.level(8.0, 10.0, 80))
        assertEquals(SpendLevel.REACHED, SpendCheck.level(10.0, 10.0, 80))
        assertEquals("\$0", SpendCheck.format(0.0))
        assertEquals("\$0.0031", SpendCheck.format(0.0031))
        assertEquals("\$12.50", SpendCheck.format(12.5))
    }

    @Test
    fun openRouterCreditFromKeyAndCredits() {
        val key = Json.parseToJsonElement("""{"data":{"limit":100,"limit_remaining":97.5,"is_free_tier":true,"usage":2.5}}""").jsonObject
        val credits = Json.parseToJsonElement("""{"data":{"total_credits":10,"total_usage":2.5}}""").jsonObject
        val c = OpenRouterCredit.parse(key, credits)
        assertEquals(7.5, c.accountRemainingUsd!!, 1e-9)
        assertEquals(10.0, c.accountPurchasedUsd!!, 1e-9)
        assertEquals(100.0, c.keyLimitUsd!!, 1e-9)
        assertEquals(97.5, c.keyRemainingUsd!!, 1e-9)
        assertTrue(c.freeTier!!)

        val noLimit = OpenRouterCredit.parse(Json.parseToJsonElement("""{"data":{"limit":null,"limit_remaining":null}}""").jsonObject, null)
        assertNull(noLimit.keyLimitUsd)
        assertNull(noLimit.accountRemainingUsd)
    }
}
