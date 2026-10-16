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
    fun noFreeChatModelMeansNoPick() {
        assertNull(StarterTeam.pickModel("ollama-local", listOf(model("nomic-embed-text")), testBackend()))
    }

    @Test
    fun strongestWritersGetLyricistAndImplementer() {
        val roles = StarterTeam.assignRoles(listOf("ollama-local", "gemini", "groq", "relay-claude"))

        assertEquals(Role.LYRICIST, roles["gemini"])
        assertEquals(Role.IMPLEMENTER, roles["groq"])
        assertEquals(Role.REVIEWER, roles["ollama-local"])
        assertEquals(Role.REVIEWER, roles["relay-claude"])
    }

    @Test
    fun relayOnlyTeamStillHasALyricist() {
        assertEquals(Role.LYRICIST, StarterTeam.assignRoles(listOf("relay-claude", "relay-chatgpt"))["relay-claude"])
    }

    @Test
    fun fallbacksAreTheOtherApiModelsStrongestFirst() {
        val refs = mapOf("ollama-local" to ModelRef(1, "llama3.2"), "gemini" to ModelRef(2, "flash"), "groq" to ModelRef(3, "gpt-oss"))

        assertEquals(listOf(ModelRef(3, "gpt-oss"), ModelRef(1, "llama3.2")), StarterTeam.fallbacksFor("gemini", refs))
    }

    @Test
    fun requestEstimateCoversEveryStage() {
        val config = RoomConfig(reviewerIds = listOf(1, 2), lyricistId = 1, proposeStyles = true, implementerId = 1, finaliserId = 3, roundCap = 3)

        // Participant 1: lyrics 1 + (3 reviews + 1 proposal) + 3 implements = 8; participant 2: 4; finaliser 3: 1.
        assertEquals(mapOf(1L to 8, 2L to 4, 3L to 1), WritersRoom.estimateRequests(config))
    }
}
