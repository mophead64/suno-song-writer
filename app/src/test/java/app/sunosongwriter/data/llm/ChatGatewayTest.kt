package app.sunosongwriter.data.llm

import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.FreeAccess
import app.sunosongwriter.domain.model.Limits
import app.sunosongwriter.domain.model.ModelInfo
import app.sunosongwriter.domain.model.ModelRef
import app.sunosongwriter.domain.usage.QuotaTracker
import app.sunosongwriter.domain.usage.RequestPacer
import app.sunosongwriter.testBackend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatGatewayTest {
    private val ref = ModelRef(1, "model-a")
    private val hello = listOf(ChatMessage(ChatMessage.Role.USER, "hello"))
    private val ok = listOf(ChatEvent.Delta("hi"), ChatEvent.Completed("stop", null))

    private class FakeTransport(vararg responses: List<ChatEvent>) : ChatTransport {
        val queue = ArrayDeque(responses.toList())
        var calls = 0
        override fun stream(endpoint: Endpoint, model: String, messages: List<ChatMessage>, options: ChatOptions) =
            flowOf(*queue.removeFirst().toTypedArray()).also { calls++ }
    }

    private class FakeCounter(var count: Int = 0) : RequestCounter {
        override suspend fun countToday(backend: Backend, modelId: String) = count
        override suspend fun increment(backend: Backend, modelId: String) {
            count++
        }
        override fun nextResetMillis(backend: Backend) = 86_400_000L
    }

    private fun TestScope.gateway(
        transport: ChatTransport,
        backend: Backend = testBackend(),
        model: ModelInfo? = null,
        apiKey: String? = "key",
        counter: RequestCounter = FakeCounter(),
        freeOnly: Boolean = true,
        quotas: QuotaTracker = QuotaTracker { testScheduler.currentTime },
        ledger: UsageLedger = UsageLedger.None,
    ) = ChatGateway(
        transport = transport,
        targets = { ResolvedTarget(backend, model, Endpoint(backend.baseUrl, apiKey)) },
        counter = counter,
        pacer = RequestPacer { testScheduler.currentTime },
        quotas = quotas,
        freeOnly = { freeOnly },
        nowMillis = { testScheduler.currentTime },
        ledger = ledger,
    )

    private class FakeLedger(val block: LlmError? = null) : UsageLedger {
        val recorded = mutableListOf<UsageEvent>()
        val checked = mutableListOf<UsageTag>()
        override suspend fun record(event: UsageEvent) {
            recorded += event
        }
        override suspend fun spendBlock(tag: UsageTag): LlmError? = block.also { checked += tag }
    }

    @Test
    fun recordsUsageOncePerFinishedRequestWithItsTag() = runTest {
        val ledger = FakeLedger()
        val reply = listOf(ChatEvent.Delta("hi"), ChatEvent.Usage(120, 30, 0.0021), ChatEvent.Completed("stop", null))
        val tag = UsageTag(songId = 7, sessionId = 3, messageId = 11)

        gateway(FakeTransport(reply), ledger = ledger).stream(ref, hello, tag = tag).toList()

        val e = ledger.recorded.single()
        assertEquals(tag, e.tag)
        assertEquals(120, e.inputTokens)
        assertEquals(30, e.outputTokens)
        assertEquals(0.0021, e.reportedCostUsd!!, 1e-9)
        assertTrue(e.isFree)
        // Free requests are never checked against spend limits.
        assertTrue(ledger.checked.isEmpty())
    }

    @Test
    fun aReachedSpendLimitStopsPaidRequestsBeforeSending() = runTest {
        val transport = FakeTransport(ok)
        val paid = testBackend(freeAccess = FreeAccess.PRICING)
        val model = ModelInfo(1, "model-a", "A", pricedFree = false, contextLength = null, available = true, lastSeen = 0)
        val limit = LlmError.SpendLimit("over")

        val events = gateway(transport, backend = paid, model = model, freeOnly = false, ledger = FakeLedger(limit)).stream(ref, hello).toList()

        assertEquals(listOf(ChatEvent.Failed(limit)), events)
        assertEquals(0, transport.calls)
    }

    @Test
    fun passesThroughASuccessfulReplyAndCountsIt() = runTest {
        val counter = FakeCounter()
        val events = gateway(FakeTransport(ok), counter = counter).stream(ref, hello).toList()

        assertEquals(ok, events)
        assertEquals(1, counter.count)
    }

    @Test
    fun freeOnlyBlocksPaidModelsBeforeSending() = runTest {
        val transport = FakeTransport(ok)
        val paid = testBackend(freeAccess = FreeAccess.PRICING)
        val model = ModelInfo(1, "model-a", "A", pricedFree = false, contextLength = null, available = true, lastSeen = 0)

        val events = gateway(transport, backend = paid, model = model).stream(ref, hello).toList()

        assertEquals(listOf(ChatEvent.Failed(LlmError.PaidModelBlocked("model-a"))), events)
        assertEquals(0, transport.calls)
    }

    @Test
    fun paidModelsAreAllowedWhenFreeOnlyIsOff() = runTest {
        val paid = testBackend(isFreeTier = false)

        val events = gateway(FakeTransport(ok), backend = paid, freeOnly = false).stream(ref, hello).toList()

        assertEquals(ok, events)
    }

    @Test
    fun missingKeyFailsFast() = runTest {
        val events = gateway(FakeTransport(ok), apiKey = null).stream(ref, hello).toList()

        assertEquals(listOf(ChatEvent.Failed(LlmError.MissingKey)), events)
    }

    @Test
    fun localDailyCountAtLimitFailsWithQuotaExhausted() = runTest {
        val transport = FakeTransport(ok)
        val backend = testBackend(limits = Limits(rpd = 5))

        val events = gateway(transport, backend = backend, counter = FakeCounter(count = 5)).stream(ref, hello).toList()

        val error = (events.single() as ChatEvent.Failed).error as LlmError.QuotaExhausted
        assertEquals(86_400_000L, error.resetAtMillis)
        assertEquals(0, transport.calls)
    }

    @Test
    fun retriesAfterRateLimitUsingRetryAfter() = runTest {
        val limited = listOf(ChatEvent.Failed(LlmError.RateLimited(retryAfterSeconds = 3.0, info = null, detail = "")))
        val transport = FakeTransport(limited, ok)

        val events = gateway(transport).stream(ref, hello).toList()

        assertEquals(listOf(ChatEvent.RateLimited(3, 1)) + ok, events)
        assertEquals(2, transport.calls)
        assertEquals(3_000, testScheduler.currentTime)
    }

    @Test
    fun givesUpAfterMaxRetries() = runTest {
        val limited = listOf(ChatEvent.Failed(LlmError.RateLimited(1.0, null, "")))
        val transport = FakeTransport(limited, limited, limited, limited)

        val events = gateway(transport).stream(ref, hello).toList()

        assertEquals(4, transport.calls)
        assertTrue((events.last() as ChatEvent.Failed).error is LlmError.RateLimited)
    }

    @Test
    fun zeroDailyRemainingFromDailyHeaderProviderBecomesQuotaExhaustedAndBlocksNextCall() = runTest {
        val backend = testBackend(dailyRemainingHeader = true)
        val info = RateLimitInfo(remainingRequests = 0, resetRequestsSeconds = 60.0)
        val transport = FakeTransport(listOf(ChatEvent.Failed(LlmError.RateLimited(2.0, info, ""))))
        val quotas = QuotaTracker { testScheduler.currentTime }
        val gateway = gateway(transport, backend = backend, quotas = quotas)

        val first = (gateway.stream(ref, hello).toList().single() as ChatEvent.Failed).error
        val second = (gateway.stream(ref, hello).toList().single() as ChatEvent.Failed).error

        assertEquals(LlmError.QuotaExhausted(60_000, ""), first)
        assertTrue(second is LlmError.QuotaExhausted)
        assertEquals(1, transport.calls)
    }

    @Test
    fun pacesRequestsOverThePerMinuteLimit() = runTest {
        val backend = testBackend(limits = Limits(rpm = 2))
        val transport = FakeTransport(ok, ok, ok)
        val gateway = gateway(transport, backend = backend)

        gateway.stream(ref, hello).toList()
        gateway.stream(ref, hello).toList()
        val third = gateway.stream(ref, hello).toList()

        assertEquals(ChatEvent.RateLimited(60, 0), third.first())
        assertEquals(60_000, testScheduler.currentTime)
    }
}
