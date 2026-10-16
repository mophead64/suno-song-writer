package app.sunosongwriter.domain.usage

import app.sunosongwriter.data.llm.RateLimitInfo
import app.sunosongwriter.domain.model.Backend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How many requests are left today, and whether that's reported by the provider or estimated locally. */
data class RequestsLeft(val remaining: Int, val limit: Int?, val estimated: Boolean)

/**
 * Daily quota as reported by providers (spec 5.2), kept in memory and keyed like
 * [Backend.limitKey]. Local counts are the fallback when a provider reports nothing.
 */
class QuotaTracker(private val nowMillis: () -> Long) {
    data class Reported(val remaining: Int, val limit: Int?, val resetAtMillis: Long?)

    private val reported = MutableStateFlow<Map<String, Reported>>(emptyMap())
    private val exhausted = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Changes whenever reported or exhausted state changes, so screens can recompute. */
    val changes: StateFlow<Map<String, Reported>> = reported.asStateFlow()

    fun record(backend: Backend, modelId: String, info: RateLimitInfo?) {
        if (!backend.dailyRemainingHeader) return
        val remaining = info?.remainingRequests ?: return
        val resetAt = info.resetRequestsSeconds?.let { nowMillis() + (it * 1000).toLong() }
        reported.update { it + (backend.limitKey(modelId) to Reported(remaining, info.limitRequests, resetAt)) }
    }

    fun markExhausted(backend: Backend, modelId: String, untilMillis: Long) {
        exhausted.update { it + (backend.limitKey(modelId) to untilMillis) }
        reported.update { current ->
            val key = backend.limitKey(modelId)
            current[key]?.let { current + (key to it.copy(remaining = 0, resetAtMillis = untilMillis)) } ?: current
        }
    }

    /** When the quota is known to be used up, the time it resets; otherwise null. */
    fun exhaustedUntil(backend: Backend, modelId: String): Long? =
        exhausted.value[backend.limitKey(modelId)]?.takeIf { it > nowMillis() }

    fun requestsLeft(backend: Backend, modelId: String, localCountToday: Int, pricedFree: Boolean? = null): RequestsLeft? {
        val key = backend.limitKey(modelId)
        val rpd = backend.limitsFor(modelId, pricedFree).rpd
        if (exhaustedUntil(backend, modelId) != null) return RequestsLeft(0, rpd, estimated = false)
        reported.value[key]
            ?.takeIf { it.resetAtMillis == null || it.resetAtMillis > nowMillis() }
            ?.let { return RequestsLeft(it.remaining, it.limit ?: rpd, estimated = false) }
        return rpd?.let { RequestsLeft((it - localCountToday).coerceAtLeast(0), it, estimated = true) }
    }
}
