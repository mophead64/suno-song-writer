package app.sunosongwriter.data.llm

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiCompatibleClientTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val now = 1_700_000_000_000L
    private val client = OpenAiCompatibleClient(OkHttpClient(), json) { now }
    private lateinit var endpoint: Endpoint

    @Before
    fun setUp() {
        server.start()
        endpoint = Endpoint(baseUrl = server.url("/v1").toString(), apiKey = "sk-test")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun send(): List<ChatEvent> = runBlocking {
        client.stream(endpoint, "test-model", listOf(ChatMessage(ChatMessage.Role.USER, "hi")), ChatOptions()).toList()
    }

    private fun sse(vararg lines: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream")
        .body(lines.joinToString("") { "$it\n\n" })

    @Test
    fun streamsDeltasUsageAndCompletion() {
        server.enqueue(
            sse(
                ": OPENROUTER PROCESSING",
                """data: {"choices":[{"delta":{"role":"assistant","content":""}}]}""",
                """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
                """data: {"choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}]}""",
                """data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":2,"cost":0}}""",
                "data: [DONE]",
            ).addHeader("x-ratelimit-remaining-requests", "998").build(),
        )

        val events = send()

        assertEquals(listOf(ChatEvent.Delta("Hel"), ChatEvent.Delta("lo")), events.filterIsInstance<ChatEvent.Delta>())
        assertEquals(ChatEvent.Usage(12, 2, 0.0), events.filterIsInstance<ChatEvent.Usage>().single())
        val done = events.last() as ChatEvent.Completed
        assertEquals("stop", done.finishReason)
        assertEquals(998, done.rateLimit?.remainingRequests)
    }

    @Test
    fun sendsModelMessagesStreamFlagAndBearerKey() {
        server.enqueue(sse("data: [DONE]").build())

        send()

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        assertEquals("Bearer sk-test", request.headers["Authorization"])
        val body = json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("stream_options"))
    }

    @Test
    fun omitsStreamOptionsWhenBackendDoesNotSupportThem() {
        endpoint = endpoint.copy(streamUsage = false)
        server.enqueue(sse("data: [DONE]").build())

        send()

        val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertTrue(!body.containsKey("stream_options"))
    }

    @Test
    fun skipsMalformedChunks() {
        server.enqueue(sse("data: {not json", """data: {"choices":[{"delta":{"content":"ok"}}]}""", "data: [DONE]").build())

        val events = send()

        assertEquals(listOf(ChatEvent.Delta("ok")), events.filterIsInstance<ChatEvent.Delta>())
        assertTrue(events.last() is ChatEvent.Completed)
    }

    @Test
    fun maps429WithRetryAfterToRateLimited() {
        server.enqueue(
            MockResponse.Builder().code(429).addHeader("retry-after", "7")
                .body("""{"error":{"message":"Rate limit exceeded: 20 per minute"}}""").build(),
        )

        val error = (send().single() as ChatEvent.Failed).error as LlmError.RateLimited

        assertEquals(7.0, error.retryAfterSeconds!!, 0.0)
        assertEquals("Rate limit exceeded: 20 per minute", error.detail)
    }

    @Test
    fun maps429DailyQuotaBodyToQuotaExhausted() {
        server.enqueue(
            MockResponse.Builder().code(429)
                .body("""[{"error":{"code":429,"message":"Quota exceeded for metric: GenerateRequestsPerDayPerProjectPerModel-FreeTier"}}]""")
                .build(),
        )

        val error = (send().single() as ChatEvent.Failed).error

        assertTrue(error is LlmError.QuotaExhausted)
    }

    @Test
    fun maps401ToAuth() {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"Invalid API key"}}""").build())

        val error = (send().single() as ChatEvent.Failed).error

        assertEquals(LlmError.Auth("Invalid API key"), error)
    }

    @Test
    fun errorObjectInsideStreamFails() {
        server.enqueue(sse("""data: {"error":{"message":"Provider returned error"}}""").build())

        val error = (send().last() as ChatEvent.Failed).error

        assertEquals("Provider returned error", (error as LlmError.Unknown).detail)
    }

    @Test
    fun cancellingTheCollectorStopsASlowStream() = runBlocking {
        // 200 chunks at one per 100ms would take 20s to finish if cancellation didn't close the call.
        val chunks = List(200) { """data: {"choices":[{"delta":{"content":"x"}}]}""" }.toTypedArray()
        server.enqueue(sse(*chunks).throttleBody(64, 100, TimeUnit.MILLISECONDS).build())

        val first = withTimeout(5_000) {
            client.stream(endpoint, "m", listOf(ChatMessage(ChatMessage.Role.USER, "hi")), ChatOptions()).first()
        }

        assertEquals(ChatEvent.Delta("x"), first)
    }

    @Test
    fun refusesPlainHttpToPublicHosts() = runBlocking {
        val events = client.stream(
            Endpoint("http://api.example.com/v1"),
            "m",
            listOf(ChatMessage(ChatMessage.Role.USER, "hi")),
            ChatOptions(),
        ).toList()

        assertEquals(ChatEvent.Failed(LlmError.InsecureHttp("api.example.com")), events.single())
    }

    @Test
    fun listsOpenAiStyleModelsWithPricingAndGeminiPrefix() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """
                {"data":[
                  {"id":"models/gemini-3-flash"},
                  {"id":"vendor/big","name":"Big Model","pricing":{"prompt":"0.000002","completion":"0.000008"},"context_length":128000},
                  {"id":"vendor/small:free","pricing":{"prompt":"0","completion":"0"}}
                ]}
                """.trimIndent(),
            ).build(),
        )

        val models = client.listModels(endpoint).associateBy { it.id }

        assertEquals(setOf("gemini-3-flash", "vendor/big", "vendor/small:free"), models.keys)
        assertNull(models.getValue("gemini-3-flash").pricedFree)
        assertEquals(false, models.getValue("vendor/big").pricedFree)
        assertEquals("Big Model", models.getValue("vendor/big").displayName)
        assertEquals(128000, models.getValue("vendor/big").contextLength)
        assertEquals(true, models.getValue("vendor/small:free").pricedFree)
    }

    @Test
    fun listsTopLevelArrayAndOllamaNativeModels() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""[{"id":"openai/gpt-4.1","name":"GPT-4.1"}]""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"models":[{"name":"gemma4:31b","model":"gemma4:31b"}]}""").build())

        assertEquals(listOf("openai/gpt-4.1"), client.listModels(endpoint).map { it.id })
        val ollama = endpoint.copy(modelsUrl = server.url("/api/tags").toString())
        assertEquals(listOf("gemma4:31b"), client.listModels(ollama).map { it.id })
    }

    @Test
    fun listModelsThrowsMappedError() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":{"message":"bad key"}}""").build())

        val error = runCatching { client.listModels(endpoint) }.exceptionOrNull() as LlmException

        assertEquals(LlmError.Auth("bad key"), error.error)
    }
}
