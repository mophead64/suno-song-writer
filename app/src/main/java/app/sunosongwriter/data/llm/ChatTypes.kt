package app.sunosongwriter.data.llm

data class ChatMessage(val role: Role, val content: String) {
    enum class Role(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }
}

data class ChatOptions(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

/** Where and how to reach one OpenAI-compatible server. */
data class Endpoint(
    val baseUrl: String,
    val apiKey: String? = null,
    /** Overrides `{baseUrl}/models` for servers that list models elsewhere (e.g. GitHub Models). */
    val modelsUrl: String? = null,
    val streamUsage: Boolean = true,
    val extraHeaders: Map<String, String> = emptyMap(),
)

/** A model as listed by a server's model endpoint. */
data class RemoteModel(
    val id: String,
    val displayName: String,
    val pricedFree: Boolean?,
    val contextLength: Int?,
)

sealed interface ChatEvent {
    /** A piece of the streamed reply. */
    data class Delta(val text: String) : ChatEvent

    data class Usage(val inputTokens: Int?, val outputTokens: Int?, val costUsd: Double?) : ChatEvent

    /** Emitted by [ChatGateway] while it waits before (re)sending because of a rate limit. */
    data class RateLimited(val waitSeconds: Long, val attempt: Int) : ChatEvent

    /** Terminal: the reply finished. */
    data class Completed(val finishReason: String?, val rateLimit: RateLimitInfo?) : ChatEvent

    /** Terminal: the request failed. */
    data class Failed(val error: LlmError) : ChatEvent
}

sealed interface LlmError {
    val message: String

    data class Auth(val detail: String) : LlmError {
        override val message get() = "The server rejected the API key. $detail".trim()
    }

    data class NotFound(val detail: String) : LlmError {
        override val message get() = "Not found. Check the base URL and model. $detail".trim()
    }

    data class RateLimited(val retryAfterSeconds: Double?, val info: RateLimitInfo?, val detail: String) : LlmError {
        override val message get() = "Rate limited by the provider. $detail".trim()
    }

    data class QuotaExhausted(val resetAtMillis: Long?, val detail: String) : LlmError {
        override val message get() = "Daily request limit reached. $detail".trim()
    }

    data class InsufficientCredit(val detail: String) : LlmError {
        override val message get() = "The provider says the account is out of credit. $detail".trim()
    }

    data class BadRequest(val code: Int, val detail: String) : LlmError {
        override val message get() = "Request rejected ($code). $detail".trim()
    }

    data class Server(val code: Int, val detail: String) : LlmError {
        override val message get() = "Server error ($code). $detail".trim()
    }

    data class Network(val detail: String) : LlmError {
        override val message get() = "Couldn't reach the server. $detail".trim()
    }

    data object Timeout : LlmError {
        override val message get() = "The server took too long to respond."
    }

    data class InsecureHttp(val host: String) : LlmError {
        override val message get() =
            "Plain http:// is only allowed for local addresses. Use https:// for $host."
    }

    data class PaidModelBlocked(val modelId: String) : LlmError {
        override val message get() = "$modelId isn't free, and free-only mode is on."
    }

    data object MissingKey : LlmError {
        override val message get() = "This backend needs an API key."
    }

    data class UnknownTarget(val modelId: String) : LlmError {
        override val message get() = "The backend or model for $modelId no longer exists."
    }

    data class Unknown(val detail: String) : LlmError {
        override val message get() = detail
    }
}

class LlmException(val error: LlmError) : Exception(error.message)
