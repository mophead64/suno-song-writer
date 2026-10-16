package app.sunosongwriter.domain.model

import app.sunosongwriter.domain.workflow.RoomConfig
import app.sunosongwriter.domain.workflow.WritersRoom
import app.sunosongwriter.testBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StarterTeamTest {
    private fun model(id: String, pricedFree: Boolean? = null) = ModelInfo(1, id, id, pricedFree, null, available = true, lastSeen = 0)

    @Test
    fun picksNewestGeminiFlashButNotLiteOrNonChatModels() {
        val models = listOf("gemini-3.5-flash", "gemini-3.8-flash", "gemini-3.8-flash-lite", "gemini-3.8-flash-image", "text-embedding-004", "gemini-3.8-flash-tts").map { model(it) }

        assertEquals("gemini-3.8-flash", StarterTeam.pickModel("gemini", models, testBackend())?.modelId)
    }

    @Test
    fun picksPreferredFreeModelsOnGroqAndOpenRouter() {
        val groq = listOf("whisper-large-v3", "meta-llama/llama-prompt-guard-2-22m", "qwen/qwen3.8-27b", "openai/gpt-oss-120b").map { model(it) }
        assertEquals("openai/gpt-oss-120b", StarterTeam.pickModel("groq", groq, testBackend())?.modelId)

        val openRouter = testBackend(freeAccess = FreeAccess.PRICING)
        val listing = listOf(model("deepseek/deepseek-v4", pricedFree = false), model("meta-llama/llama-4:free", pricedFree = true), model("qwen/qwen3:free", pricedFree = true))
        assertEquals("qwen/qwen3:free", StarterTeam.pickModel("openrouter", listing, openRouter)?.modelId)
    }

    @Test
    fun ollamaPrefersOfficialModelsOverCommunityFineTunes() {
        val local = listOf("artifish/llama3.2-uncensored:latest", "llama3.2:latest", "llava:latest").map { model(it) }
        assertEquals("llama3.2:latest", StarterTeam.pickModel("ollama-local", local, testBackend())?.modelId)
        assertEquals("someone/model:latest", StarterTeam.pickModel("ollama-local", listOf(model("someone/model:latest")), testBackend())?.modelId)
    }

    @Test
    fun picksCurrentChatModelsOnDirectProviders() {
        val paid = testBackend(isFreeTier = false)
        val anthropic = listOf("claude-haiku-4-5", "claude-opus-5-5", "claude-sonnet-4-6", "claude-sonnet-5-5").map { model(it) }
        assertEquals("claude-sonnet-5-5", StarterTeam.pickModel("anthropic", anthropic, paid, freeOnly = false)?.modelId)

        val openai = listOf("dall-e-3", "gpt-5-mini", "gpt-5.2", "gpt-realtime", "omni-moderation-latest", "text-embedding-3-large", "whisper-1").map { model(it) }
        assertEquals("gpt-5.2", StarterTeam.pickModel("openai", openai, paid, freeOnly = false)?.modelId)
        // Paid providers have nothing to offer in free-only mode.
        assertNull(StarterTeam.pickModel("openai", openai, paid))
    }

    @Test
    fun noFreeChatModelMeansNoPick() {
        assertNull(StarterTeam.pickModel("ollama-local", listOf(model("nomic-embed-text")), testBackend()))
    }

    private fun seat(id: Long, preset: String?, relay: Boolean = false) = TeamSeat(id, preset, relay, ModelRef(id, "m$id"))

    @Test
    fun strongestWritersGetLyricistAndImplementer() {
        val roles = StarterTeam.assignRoles(listOf(seat(1, "ollama-local"), seat(2, "gemini"), seat(3, "groq"), seat(4, "relay-claude", relay = true)))

        assertEquals(Role.LYRICIST, roles[2])
        assertEquals(Role.IMPLEMENTER, roles[3])
        assertEquals(Role.REVIEWER, roles[1])
        assertEquals(Role.REVIEWER, roles[4])
    }

    @Test
    fun relayOnlyTeamStillHasALyricist() {
        assertEquals(Role.LYRICIST, StarterTeam.assignRoles(listOf(seat(1, "relay-claude", true), seat(2, "relay-chatgpt", true)))[1])
    }

    @Test
    fun unknownApiBackendsWriteBeforeLocalOnes() {
        val roles = StarterTeam.assignRoles(listOf(seat(1, "ollama-local"), seat(2, null)))
        assertEquals(Role.LYRICIST, roles[2])
        assertEquals(Role.IMPLEMENTER, roles[1])
    }

    @Test
    fun fallbacksAreTheOtherApiModelsStrongestFirst() {
        val seats = listOf(seat(1, "ollama-local"), seat(2, "gemini"), seat(3, "groq"), seat(4, "relay-claude", relay = true))

        assertEquals(listOf(ModelRef(3, "m3"), ModelRef(1, "m1")), StarterTeam.fallbacksFor(seats[1], seats))
        assertEquals(emptyList<ModelRef>(), StarterTeam.fallbacksFor(seats[3], seats))
    }

    @Test
    fun namesComeFromThePresetOrTheBackend() {
        assertEquals("Gemini", StarterTeam.participantName(testBackend().copy(presetId = "gemini", name = "Google AI Studio (Gemini)")))
        assertEquals("My server", StarterTeam.participantName(testBackend().copy(presetId = null, name = "My server (on your computer)")))
        assertEquals("Ollama 2", StarterTeam.uniqueName("Ollama", listOf("ollama", "Gemini")))
        assertEquals("Groq", StarterTeam.uniqueName("Groq", listOf("Gemini")))
    }

    @Test
    fun paidModelsCountWhenFreeOnlyIsOff() {
        val paid = testBackend(freeAccess = FreeAccess.PRICING)
        val listing = listOf(model("deepseek/deepseek-v4", pricedFree = false))
        assertNull(StarterTeam.pickModel("openrouter", listing, paid))
        assertEquals("deepseek/deepseek-v4", StarterTeam.pickModel("openrouter", listing, paid, freeOnly = false)?.modelId)
    }

    @Test
    fun requestEstimateCoversEveryStage() {
        val config = RoomConfig(reviewerIds = listOf(1, 2), lyricistId = 1, proposeStyles = true, implementerId = 1, finaliserId = 3, roundCap = 3)

        // Participant 1: lyrics 1 + (3 reviews + 1 proposal) + 3 implements = 8; participant 2: 4; finaliser 3: 1.
        assertEquals(mapOf(1L to 8, 2L to 4, 3L to 1), WritersRoom.estimateRequests(config))
    }
}
