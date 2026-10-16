package app.sunosongwriter.data.repo

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sunosongwriter.data.db.AppDatabase
import app.sunosongwriter.data.llm.LlmError
import app.sunosongwriter.data.llm.UsageEvent
import app.sunosongwriter.data.llm.UsageTag
import app.sunosongwriter.domain.model.AuthStyle
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.FreeAccess
import app.sunosongwriter.domain.model.LimitScope
import app.sunosongwriter.domain.model.Limits
import app.sunosongwriter.domain.usage.CostSource
import app.sunosongwriter.domain.usage.Price
import app.sunosongwriter.domain.usage.SpendLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class UsageRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
    private var now = LocalDate.parse("2026-10-16").atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val limits = MutableStateFlow(SpendLimits())
    private val usage = UsageRepository(db.usageDao(), limits, { now }, { ZoneOffset.UTC })

    @After
    fun tearDown() = db.close()

    private val backend = Backend(
        id = 4, presetId = "openrouter", name = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1", modelsUrl = null,
        auth = AuthStyle.BEARER, freeAccess = FreeAccess.PRICING, isFreeTier = false, limits = Limits(), modelLimits = emptyList(),
        limitScope = LimitScope.BACKEND, limitsFreeModelsOnly = true, dailyResetZone = "UTC", streamUsage = true,
        dailyRemainingHeader = false, extraHeaders = emptyMap(), keyHint = null,
    )

    private fun event(model: String = "paid/model", reported: Double? = null, free: Boolean = false, session: Long? = 1) =
        UsageEvent(backend, model, UsageTag(songId = 9, sessionId = session, messageId = 2), 1_000_000, 100_000, reported, free)

    @Test
    fun recordsCostsBySource() = runTest {
        usage.savePrice(Price(4, "priced/model", 3.0, 15.0))
        usage.record(event(reported = 0.25))
        usage.record(event(model = "priced/model"))
        usage.record(event(free = true))
        usage.record(event(model = "unpriced/model"))

        val records = usage.observeSince(0).first()
        assertEquals(listOf(CostSource.REPORTED, CostSource.ESTIMATED, CostSource.FREE, CostSource.UNKNOWN), records.map { it.source })
        assertEquals(4.5, records[1].costUsd!!, 1e-9)
        assertNull(records[3].costUsd)
        assertEquals(9L, records[0].songId)
        assertEquals(4.75, usage.observeMonthSpend().first(), 1e-9)
    }

    @Test
    fun spendLimitsBlockAtTheMonthlyAndPerRunLimits() = runTest {
        usage.record(event(reported = 2.0, session = 1))
        assertNull(usage.spendBlock(UsageTag(sessionId = 1)))

        limits.value = SpendLimits(sessionUsd = 2.0)
        assertTrue(usage.spendBlock(UsageTag(sessionId = 1)) is LlmError.SpendLimit)
        assertNull(usage.spendBlock(UsageTag(sessionId = 2)))

        limits.value = SpendLimits(monthlyUsd = 1.5)
        assertTrue(usage.spendBlock(UsageTag()) is LlmError.SpendLimit)

        // Last month's spending doesn't count.
        now += 31L * 24 * 3600 * 1000
        assertNull(usage.spendBlock(UsageTag()))
    }
}
