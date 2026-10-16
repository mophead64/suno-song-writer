package app.sunosongwriter.data.llm

import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RateLimitInfoTest {
    private val now = 1_700_000_000_000L

    @Test
    fun parsesGroqStyleHeaders() {
        val info = RateLimitInfo.parse(
            headersOf(
                "retry-after", "2",
                "x-ratelimit-limit-requests", "14400",
                "x-ratelimit-remaining-requests", "14370",
                "x-ratelimit-reset-requests", "2m59.56s",
            ),
            now,
        )!!

        assertEquals(2.0, info.retryAfterSeconds!!, 0.0)
        assertEquals(14400, info.limitRequests)
        assertEquals(14370, info.remainingRequests)
        assertEquals(179.56, info.resetRequestsSeconds!!, 0.001)
    }

    @Test
    fun parsesOpenRouterEpochMillisReset() {
        val info = RateLimitInfo.parse(headersOf("X-RateLimit-Reset", "${now + 30_000}"), now)!!

        assertEquals(30.0, info.resetSeconds!!, 0.001)
        assertNull(info.remainingRequests) // generic headers aren't treated as daily counts
    }

    @Test
    fun parsesDurations() {
        assertEquals(7.66, RateLimitInfo.parseDuration("7.66s")!!, 0.001)
        assertEquals(0.25, RateLimitInfo.parseDuration("250ms")!!, 0.001)
        assertEquals(3720.0, RateLimitInfo.parseDuration("1h2m")!!, 0.001)
        assertNull(RateLimitInfo.parseDuration("soon"))
        assertNull(RateLimitInfo.parseDuration("5x"))
    }

    @Test
    fun parsesHttpDateRetryAfter() {
        // 2023-11-14T22:13:20Z is `now`; ten seconds later:
        assertEquals(10.0, RateLimitInfo.parseRetryAfter("Tue, 14 Nov 2023 22:13:30 GMT", now)!!, 0.001)
    }

    @Test
    fun returnsNullWhenNoRateLimitHeaders() {
        assertNull(RateLimitInfo.parse(headersOf("content-type", "application/json"), now))
    }
}
