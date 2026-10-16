package app.sunosongwriter.data.presets

import kotlinx.serialization.json.Json
import java.io.InputStream

/** Loads the bundled backend presets. Presets are data, so they can be updated without code changes. */
class PresetRepository(
    private val json: Json,
    private val open: () -> InputStream,
) {
    val presets: List<BackendPreset> by lazy {
        open().bufferedReader().use { json.decodeFromString<PresetFile>(it.readText()).presets }
    }

    fun find(id: String?): BackendPreset? = id?.let { wanted -> presets.firstOrNull { it.id == wanted } }
}
