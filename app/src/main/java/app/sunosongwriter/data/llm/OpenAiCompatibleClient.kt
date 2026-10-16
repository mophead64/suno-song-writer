package app.sunosongwriter.data.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Talks to any server implementing OpenAI's `/chat/completions` and `/models` (spec 5.1):
 * OpenRouter, Gemini's compatibility endpoint, Groq, Mistral, Ollama, LM Studio and others.
 *
 * Requests are sent exactly once. Rate limiting, retries, quotas and free-only checks live in [ChatGateway].
 */
class OpenAiCompatibleClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ChatTransport {
    override fun stream(
        endpoint: Endpoint,
        model: String,
        messages: List<ChatMessage>,
        options: ChatOptions,
    ): Flow<ChatEvent> = callbackFlow {
        val url = resolve(endpoint.baseUrl, "chat/completions")
        val failure = checkUrl(url, endpoint.baseUrl)
        if (failure != null) {
            trySend(ChatEvent.Failed(failure))
            close()
            return@callbackFlow
        }
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            if (endpoint.streamUsage) put("stream_options", buildJsonObject { put("include_usage", true) })
            options.temperature?.let { put("temperature", it) }
            options.maxTokens?.let { put("max_tokens", it) }
            put("messages", buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role.wire)
                        put("content", m.content)
                    })
                }
            })
        }
        val request = requestBuilder(url!!, endpoint)
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val call = http.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) trySend(ChatEvent.Failed(mapIoError(e)))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) {
                            trySend(ChatEvent.Failed(mapHttpError(response)))
                        } else {
                            readStream(response) { trySend(it) }
                        }
                    } catch (e: IOException) {
                        if (!call.isCanceled()) trySend(ChatEvent.Failed(mapIoError(e)))
                    }
                }
                close()
            }
        })
        awaitClose { call.cancel() }
    }.buffer(Channel.UNLIMITED) // trySend from OkHttp's thread must never drop a chunk

    suspend fun listModels(endpoint: Endpoint): List<RemoteModel> = withContext(Dispatchers.IO) {
        val url = endpoint.modelsUrl?.toHttpUrlOrNull() ?: resolve(endpoint.baseUrl, "models")
        checkUrl(url, endpoint.modelsUrl ?: endpoint.baseUrl)?.let { throw LlmException(it) }
        val request = requestBuilder(url!!, endpoint).get().build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw LlmException(mapHttpError(response))
                parseModels(json.parseToJsonElement(response.body.string()))
            }
        } catch (e: IOException) {
            throw LlmException(mapIoError(e))
        } catch (e: IllegalArgumentException) {
            throw LlmException(LlmError.Unknown("Unexpected model list format: ${e.message}"))
        }
    }

    /**
     * OpenRouter's remaining credit (spec 5.17): the account's balance from `/credits`, and this key's own
     * limit from `/key`. Either half can be missing; `/key` failing is an error.
     */
    suspend fun openRouterCredit(endpoint: Endpoint): OpenRouterCredit = withContext(Dispatchers.IO) {
        val key = getJson(endpoint, "key") ?: throw LlmException(LlmError.Unknown("Unexpected reply from OpenRouter's /key."))
        val credits = try {
            getJson(endpoint, "credits")
        } catch (_: LlmException) {
            null // some keys can't read the account's credits
        }
        OpenRouterCredit.parse(key, credits)
    }

    private fun getJson(endpoint: Endpoint, path: String): JsonObject? {
        val url = resolve(endpoint.baseUrl, path)
        checkUrl(url, endpoint.baseUrl)?.let { throw LlmException(it) }
        val request = requestBuilder(url!!, endpoint).get().build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw LlmException(mapHttpError(response))
                json.parseToJsonElement(response.body.string()) as? JsonObject
            }
        } catch (e: IOException) {
            throw LlmException(mapIoError(e))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun readStream(response: Response, emit: (ChatEvent) -> Unit) {
        val source = response.body.source()
        var finishReason: String? = null
        var lastUsage: ChatEvent.Usage? = null
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue // blank lines, comments (": PROCESSING"), event names
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            if (data.isEmpty()) continue
            val chunk = try {
                json.parseToJsonElement(data).jsonObject
            } catch (_: IllegalArgumentException) {
                continue // tolerate malformed chunks
            }
            chunk["error"]?.let { error ->
                emit(ChatEvent.Failed(errorFromBody(response.code, error.toString(), response)))
                return
            }
            val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            (choice?.get("delta") as? JsonObject)?.get("content")?.jsonPrimitiveOrNull()?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let { emit(ChatEvent.Delta(it)) }
            choice?.get("finish_reason")?.jsonPrimitiveOrNull()?.contentOrNull?.let { finishReason = it }
            (chunk["usage"] as? JsonObject)?.let { usage ->
                lastUsage = ChatEvent.Usage(
                    inputTokens = usage["prompt_tokens"]?.jsonPrimitiveOrNull()?.intOrNull,
                    outputTokens = usage["completion_tokens"]?.jsonPrimitiveOrNull()?.intOrNull,
                    costUsd = usage["cost"]?.jsonPrimitiveOrNull()?.doubleOrNull,
                )
            }
        }
        lastUsage?.let(emit)
        emit(ChatEvent.Completed(finishReason, RateLimitInfo.parse(response.headers, nowMillis())))
    }

    private fun parseModels(root: JsonElement): List<RemoteModel> {
        // OpenAI: {"data":[{"id":…}]}; GitHub Models: [{"id":…}]; Ollama native: {"models":[{"name":…}]}
        val items = when {
            root is JsonArray -> root
            root is JsonObject && root["data"] is JsonArray -> root["data"]!!.jsonArray
            root is JsonObject && root["models"] is JsonArray -> root["models"]!!.jsonArray
            else -> throw IllegalArgumentException("no model array")
        }
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val rawId = obj.string("id") ?: obj.string("model") ?: obj.string("name") ?: return@mapNotNull null
            val id = rawId.removePrefix("models/") // Gemini lists "models/gemini-…" but expects the bare id
            val pricing = obj["pricing"] as? JsonObject
            val pricedFree = when {
                id.endsWith(":free") -> true
                pricing != null -> isZero(pricing["prompt"]) && isZero(pricing["completion"])
                else -> null
            }
            RemoteModel(
                id = id,
                displayName = obj.string("name")?.takeIf { it != rawId } ?: obj.string("display_name") ?: id,
                pricedFree = pricedFree,
                contextLength = obj["context_length"]?.jsonPrimitiveOrNull()?.intOrNull,
            )
        }.distinctBy { it.id }.sortedBy { it.id }
    }

    private fun isZero(value: JsonElement?): Boolean =
        value?.jsonPrimitiveOrNull()?.contentOrNull?.toDoubleOrNull() == 0.0

    private fun requestBuilder(url: HttpUrl, endpoint: Endpoint): Request.Builder =
        Request.Builder().url(url).apply {
            endpoint.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            endpoint.extraHeaders.forEach { (name, value) -> header(name, value) }
        }

    private fun checkUrl(url: HttpUrl?, raw: String): LlmError? = when {
        url == null -> LlmError.Network("\"$raw\" isn't a valid URL.")
        !CleartextPolicy.isAllowed(url) -> LlmError.InsecureHttp(url.host)
        else -> null
    }

    private fun mapHttpError(response: Response): LlmError =
        errorFromBody(response.code, response.body.string(), response)

    private fun errorFromBody(code: Int, body: String, response: Response): LlmError {
        val detail = extractMessage(body)
        val info = RateLimitInfo.parse(response.headers, nowMillis())
        return when (code) {
            401, 403 -> LlmError.Auth(detail)
            402 -> LlmError.InsufficientCredit(detail)
            404 -> LlmError.NotFound(detail)
            429 -> if (looksLikeDailyQuota(body)) {
                LlmError.QuotaExhausted(resetAtMillis = null, detail = detail)
            } else {
                LlmError.RateLimited(info?.retryAfterSeconds, info, detail)
            }
            in 500..599 -> LlmError.Server(code, detail)
            200 -> LlmError.Unknown(detail) // error object inside a successful stream
            else -> LlmError.BadRequest(code, detail)
        }
    }

    private fun extractMessage(body: String): String {
        val fromJson = try {
            val root = json.parseToJsonElement(body)
            val error = (root as? JsonObject)?.get("error") ?: root
            when (error) {
                is JsonObject -> error.string("message")
                is JsonArray -> ((error.firstOrNull() as? JsonObject)?.get("error") as? JsonObject)?.string("message")
                is JsonPrimitive -> error.contentOrNull
            }
        } catch (_: IllegalArgumentException) {
            null
        }
        return (fromJson ?: body).trim().take(300)
    }

    private fun mapIoError(e: IOException): LlmError = when (e) {
        is SocketTimeoutException -> LlmError.Timeout
        is UnknownHostException -> LlmError.Network("Unknown host: ${e.message}")
        is ConnectException -> LlmError.Network(e.message ?: "Connection refused.")
        is InterruptedIOException -> LlmError.Timeout
        else -> LlmError.Network(e.message ?: e.javaClass.simpleName)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Gemini and Groq phrase daily caps as "per day"; OpenRouter as "free-models-per-day". */
        fun looksLikeDailyQuota(body: String): Boolean {
            val b = body.lowercase()
            return "perday" in b || "per day" in b || "per-day" in b || "daily" in b
        }

        fun resolve(baseUrl: String, path: String): HttpUrl? =
            baseUrl.trim().trimEnd('/').let { "$it/$path" }.toHttpUrlOrNull()
    }
}

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
