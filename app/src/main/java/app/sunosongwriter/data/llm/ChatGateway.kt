package app.sunosongwriter.data.llm

import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.ModelInfo
import app.sunosongwriter.domain.model.ModelRef
import app.sunosongwriter.domain.usage.QuotaTracker
import app.sunosongwriter.domain.usage.RequestPacer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil

/** Sends one chat request. Implemented by [OpenAiCompatibleClient]; faked in tests. */
fun interface ChatTransport {
    fun stream(endpoint: Endpoint, model: String, messages: List<ChatMessage>, options: ChatOptions): Flow<ChatEvent>
}

data class ResolvedTarget(val backend: Backend, val model: ModelInfo?, val endpoint: Endpoint)

fun interface TargetResolver {
    suspend fun resolve(ref: ModelRef): ResolvedTarget?
}

interface RequestCounter {
    suspend fun countToday(backend: Backend, modelId: String): Int
    suspend fun increment(backend: Backend, modelId: String)
    fun nextResetMillis(backend: Backend): Long
}

/**
 * The only way the app calls a model. Applies, in order (spec 5.1, 5.2):
 * 1. free-only mode: paid models are blocked here, not just hidden in pickers
 * 2. API key presence
 * 3. daily quota: provider-reported exhaustion, then the local count against the limit
 * 4. per-minute pacing, staggering requests that share a backend
 * 5. 429 handling: wait (`Retry-After`, reset headers, or exponential back-off) and retry,
 *    emitting [ChatEvent.RateLimited] so the UI can say what's happening
 */
class ChatGateway(
    private val transport: ChatTransport,
    private val targets: TargetResolver,
    private val counter: RequestCounter,
    private val pacer: RequestPacer,
    private val quotas: QuotaTracker,
    private val freeOnly: suspend () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxRetries: Int = 3,
) {
    fun stream(ref: ModelRef, messages: List<ChatMessage>, options: ChatOptions = ChatOptions()): Flow<ChatEvent> = flow {
        val target = targets.resolve(ref)
        if (target == null) {
            emit(ChatEvent.Failed(LlmError.UnknownTarget(ref.modelId)))
            return@flow
        }
        val backend = target.backend
        if (backend.isRelay) {
            emit(ChatEvent.Failed(LlmError.Unknown("${backend.name} is a relay participant: its replies come through the chat app, not an API.")))
            return@flow
        }
        if (freeOnly() && !backend.isFree(ref.modelId, target.model?.pricedFree)) {
            emit(ChatEvent.Failed(LlmError.PaidModelBlocked(ref.modelId)))
            return@flow
        }
        if (backend.needsKey && target.endpoint.apiKey.isNullOrBlank()) {
            emit(ChatEvent.Failed(LlmError.MissingKey))
            return@flow
        }
        val limits = backend.limitsFor(ref.modelId, target.model?.pricedFree)
        quotaError(backend, ref.modelId, limits.rpd)?.let {
            emit(ChatEvent.Failed(it))
            return@flow
        }

        var attempt = 0
        while (true) {
            val pace = pacer.reserve(backend.limitKey(ref.modelId), limits.rpm)
            if (pace > 0) {
                emit(ChatEvent.RateLimited(seconds(pace), attempt))
                delay(pace)
            }
            counter.increment(backend, ref.modelId)

            var retryAfter: Long? = null
            var sawContent = false
            transport.stream(target.endpoint, ref.modelId, messages, options).collect { event ->
                when (event) {
                    is ChatEvent.Delta -> {
                        sawContent = true
                        emit(event)
                    }
                    is ChatEvent.Completed -> {
                        quotas.record(backend, ref.modelId, event.rateLimit)
                        emit(event)
                    }
                    is ChatEvent.Failed -> {
                        val error = classify(backend, ref.modelId, event.error)
                        if (error is LlmError.RateLimited && !sawContent && attempt < maxRetries) {
                            retryAfter = retryDelay(error, attempt)
                        } else {
                            emit(ChatEvent.Failed(error))
                        }
                    }
                    else -> emit(event)
                }
            }
            val wait = retryAfter ?: break
            attempt++
            emit(ChatEvent.RateLimited(seconds(wait), attempt))
            delay(wait)
        }
    }

    private suspend fun quotaError(backend: Backend, modelId: String, rpd: Int?): LlmError? {
        quotas.exhaustedUntil(backend, modelId)?.let { until ->
            return LlmError.QuotaExhausted(until, "Resets ${formatTime(until)}.")
        }
        if (rpd != null && counter.countToday(backend, modelId) >= rpd) {
            val reset = counter.nextResetMillis(backend)
            return LlmError.QuotaExhausted(reset, "This app has sent $rpd requests today. Resets ${formatTime(reset)}.")
        }
        return null
    }

    /** Fills in reset times, and treats "0 left today" 429s from daily-header providers as quota exhaustion. */
    private fun classify(backend: Backend, modelId: String, error: LlmError): LlmError {
        val daily = when (error) {
            is LlmError.QuotaExhausted -> error
            is LlmError.RateLimited ->
                if (backend.dailyRemainingHeader && error.info?.remainingRequests == 0) {
                    LlmError.QuotaExhausted(
                        error.info.resetRequestsSeconds?.let { nowMillis() + (it * 1000).toLong() },
                        error.detail,
                    )
                } else {
                    null
                }
            else -> null
        } ?: return error
        val resetAt = daily.resetAtMillis ?: counter.nextResetMillis(backend)
        quotas.markExhausted(backend, modelId, resetAt)
        return daily.copy(resetAtMillis = resetAt)
    }

    private fun retryDelay(error: LlmError.RateLimited, attempt: Int): Long {
        val hinted = error.retryAfterSeconds ?: error.info?.resetSeconds
        val millis = hinted?.let { (it * 1000).toLong() } ?: (BACKOFF_BASE_MILLIS shl attempt)
        return millis.coerceIn(MIN_WAIT_MILLIS, MAX_WAIT_MILLIS)
    }

    private fun seconds(millis: Long): Long = ceil(millis / 1000.0).toLong()

    private fun formatTime(millis: Long): String =
        "at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

    private companion object {
        const val BACKOFF_BASE_MILLIS = 2_000L
        const val MIN_WAIT_MILLIS = 500L
        const val MAX_WAIT_MILLIS = 120_000L
    }
}
