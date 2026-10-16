package app.sunosongwriter.data.presets

import app.sunosongwriter.data.llm.CleartextPolicy
import app.sunosongwriter.domain.model.AuthStyle
import app.sunosongwriter.domain.usage.DailyWindow
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** Validates the bundled `assets/presets.json`, so a bad edit fails the build rather than the app. */
class PresetRepositoryTest {
    private val repo = PresetRepository(Json { ignoreUnknownKeys = true }) {
        File("src/main/assets/presets.json").inputStream()
    }

    @Test
    fun presetsParseWithUniqueIds() {
        val ids = repo.presets.map { it.id }
        assertTrue(ids.size >= 8)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun includesTheFreeBackendsTheSpecNames() {
        listOf("ollama-local", "openrouter", "gemini", "groq", "mistral", "github-models", "custom")
            .forEach { assertTrue(it, repo.find(it) != null) }
    }

    @Test
    fun urlsAreValidAndRemoteOnesUseHttps() {
        repo.presets.filter { it.baseUrl.isNotEmpty() }.forEach { preset ->
            val url = preset.baseUrl.toHttpUrl()
            assertTrue(preset.id, CleartextPolicy.isAllowed(url))
            if (!preset.local) assertTrue("${preset.id} must use https", url.isHttps)
            preset.modelsUrl?.let { assertTrue(it.toHttpUrl().isHttps) }
        }
    }

    @Test
    fun timeZonesAreRealAndLocalPresetsNeedNoKey() {
        repo.presets.forEach { preset ->
            assertEquals(preset.dailyResetZone, ZoneId.of(preset.dailyResetZone).id)
            if (preset.local) assertEquals(AuthStyle.NONE, preset.auth)
            assertTrue(preset.dataUseNote.isNotBlank())
            DailyWindow.dayKey(preset.dailyResetZone, 0)
        }
    }
}
