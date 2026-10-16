package app.sunosongwriter.domain.model

/** A free source the starter-team wizard can add (spec 5.2). */
data class StarterSource(
    val presetId: String,
    val participantName: String,
    val avatar: String,
    val needsKey: Boolean,
    val isRelay: Boolean,
    val isLocal: Boolean = false,
)

/** Rules for building a free starter team: which model to use per provider, and who gets which role. */
object StarterTeam {
    val sources = listOf(
        StarterSource("ollama-local", "Ollama", "🦙", needsKey = false, isRelay = false, isLocal = true),
        StarterSource("gemini", "Gemini", "✨", needsKey = true, isRelay = false),
        StarterSource("openrouter", "OpenRouter", "🔀", needsKey = true, isRelay = false),
        StarterSource("groq", "Groq", "⚡", needsKey = true, isRelay = false),
        StarterSource("relay-claude", "Claude", "🎻", needsKey = false, isRelay = true),
        StarterSource("relay-chatgpt", "ChatGPT", "💬", needsKey = false, isRelay = true),
        StarterSource("relay-gemini", "Gemini app", "🌟", needsKey = false, isRelay = true),
        StarterSource("relay-grok", "Grok", "🛰️", needsKey = false, isRelay = true),
        StarterSource("relay-deepseek", "DeepSeek", "🐋", needsKey = false, isRelay = true),
    )

    /** Strongest free writer first: who writes lyrics, implements and finalises. */
    private val writerPreference = listOf("gemini", "groq", "openrouter", "ollama-local", "lmstudio-local")

    private val notForText = listOf("embed", "tts", "whisper", "orpheus", "guard", "image", "vision", "llava", "moondream", "audio", "live", "transcribe")

    /** Picks a sensible free chat model for a newly added backend, or null if there isn't one. */
    fun pickModel(presetId: String, models: List<ModelInfo>, backend: Backend): ModelInfo? {
        val usable = models.filter { it.available && backend.isFree(it) && notForText.none { word -> it.modelId.contains(word, ignoreCase = true) } }
        fun preferring(vararg words: String) = words.firstNotNullOfOrNull { w -> usable.firstOrNull { it.modelId.contains(w, ignoreCase = true) } }
        return when (presetId) {
            // Newest Flash (not Lite) first; ids sort so that newer versions come later.
            "gemini" -> usable.filter { "flash" in it.modelId && "lite" !in it.modelId }.maxByOrNull { it.modelId } ?: usable.maxByOrNull { it.modelId }
            "groq" -> preferring("gpt-oss-120b", "qwen", "gpt-oss-20b", "llama")
            "openrouter" -> preferring("deepseek", "qwen", "llama", "gemma", "mistral")
            else -> usable.firstOrNull()
        } ?: usable.firstOrNull()
    }

    /** Lyricist and implementer go to the strongest free writers; everyone else reviews. */
    fun assignRoles(presetIds: List<String>): Map<String, Role> {
        val apiWriters = writerPreference.filter { it in presetIds }
        val roles = presetIds.associateWith { Role.REVIEWER }.toMutableMap()
        val lyricist = apiWriters.firstOrNull() ?: presetIds.firstOrNull { it.startsWith("relay-") }
        val implementer = apiWriters.getOrNull(1) ?: apiWriters.firstOrNull()
        lyricist?.let { roles[it] = Role.LYRICIST }
        if (implementer != null && implementer != lyricist) roles[implementer] = Role.IMPLEMENTER
        return roles
    }

    /** Each API participant falls back to the other free API participants, strongest first. */
    fun fallbacksFor(presetId: String, refs: Map<String, ModelRef>): List<ModelRef> =
        writerPreference.filter { it != presetId }.mapNotNull(refs::get)
}
