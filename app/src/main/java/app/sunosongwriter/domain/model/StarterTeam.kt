package app.sunosongwriter.domain.model

/** A backend the team builder turns into a participant, with the model it picked. */
data class TeamSeat(val backendId: Long, val presetId: String?, val isRelay: Boolean, val model: ModelRef)

/**
 * Rules for building a team from the backends the user has added (spec 5.2): which model each one
 * uses, what the participant is called, and who gets which role. Keys and addresses live on the
 * backends; the team builder never asks for them.
 */
object StarterTeam {
    /** Participant names and avatars for known presets; other backends use their own name. */
    private val looks = mapOf(
        "ollama-local" to ("Ollama" to "🦙"),
        "lmstudio-local" to ("LM Studio" to "🖥️"),
        "gemini" to ("Gemini" to "✨"),
        "openrouter" to ("OpenRouter" to "🔀"),
        "groq" to ("Groq" to "⚡"),
        "mistral" to ("Mistral" to "🌬️"),
        "github-models" to ("GitHub Models" to "🐙"),
        "cerebras" to ("Cerebras" to "🧠"),
        "ollama-cloud" to ("Ollama Cloud" to "☁️"),
        "relay-claude" to ("Claude" to "🎻"),
        "relay-chatgpt" to ("ChatGPT" to "💬"),
        "relay-gemini" to ("Gemini app" to "🌟"),
        "relay-grok" to ("Grok" to "🛰️"),
        "relay-deepseek" to ("DeepSeek" to "🐋"),
    )

    /** Strongest writer first: who writes lyrics, implements and finalises. Unknown API backends sit before local ones. */
    private val writerPreference = listOf("gemini", "groq", "openrouter", "mistral", "github-models", "cerebras", "ollama-cloud", null, "ollama-local", "lmstudio-local")

    private val notForText = listOf("embed", "tts", "whisper", "orpheus", "guard", "image", "vision", "llava", "moondream", "audio", "live", "transcribe")

    fun participantName(backend: Backend): String =
        looks[backend.presetId]?.first ?: backend.name.replace(Regex("""\s*\((?:on your computer|relay)\)\s*$"""), "").trim().ifEmpty { backend.name }

    fun avatar(backend: Backend): String = looks[backend.presetId]?.second ?: if (backend.isRelay) "💬" else "🤖"

    /** A name not already taken: "Gemini", then "Gemini 2"… */
    fun uniqueName(base: String, taken: Collection<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (base.lowercase() !in lower) return base
        return (2..99).map { "$base $it" }.first { it.lowercase() !in lower }
    }

    /**
     * A sensible chat model for a backend, or null if it has none. Only free models while [freeOnly] is
     * on; with it off, paid models count too.
     */
    fun pickModel(presetId: String?, models: List<ModelInfo>, backend: Backend, freeOnly: Boolean = true): ModelInfo? {
        val usable = models.filter { it.available && (!freeOnly || backend.isFree(it)) && notForText.none { word -> it.modelId.contains(word, ignoreCase = true) } }
        fun preferring(vararg words: String) = words.firstNotNullOfOrNull { w -> usable.firstOrNull { it.modelId.contains(w, ignoreCase = true) } }
        return when (presetId) {
            // Newest Flash (not Lite) first; ids sort so that newer versions come later.
            "gemini" -> usable.filter { "flash" in it.modelId && "lite" !in it.modelId }.maxByOrNull { it.modelId } ?: usable.maxByOrNull { it.modelId }
            "groq" -> preferring("gpt-oss-120b", "qwen", "gpt-oss-20b", "llama")
            "openrouter" -> preferring("deepseek", "qwen", "llama", "gemma", "mistral")
            // Official library models ("llama3.2") before community fine-tunes ("someone/llama3.2-uncensored").
            "ollama-local", "lmstudio-local" -> usable.firstOrNull { '/' !in it.modelId }
            else -> usable.firstOrNull()
        } ?: usable.firstOrNull()
    }

    private fun rank(seat: TeamSeat): Int =
        writerPreference.indexOf(seat.presetId?.takeIf { it in writerPreference }).takeIf { it >= 0 } ?: writerPreference.indexOf(null)

    /** API seats, strongest writer first. */
    private fun writers(seats: List<TeamSeat>) = seats.filter { !it.isRelay }.sortedBy(::rank)

    /** Lyricist and implementer go to the strongest writers; everyone else reviews. A relay-only team still gets a lyricist. */
    fun assignRoles(seats: List<TeamSeat>): Map<Long, Role> {
        val roles = seats.associate { it.backendId to Role.REVIEWER }.toMutableMap()
        val apiWriters = writers(seats)
        val lyricist = apiWriters.firstOrNull() ?: seats.firstOrNull { it.isRelay }
        val implementer = apiWriters.getOrNull(1) ?: apiWriters.firstOrNull()
        lyricist?.let { roles[it.backendId] = Role.LYRICIST }
        if (implementer != null && implementer != lyricist) roles[implementer.backendId] = Role.IMPLEMENTER
        return roles
    }

    /** Each API participant falls back to the other API participants, strongest first. Relay participants get none. */
    fun fallbacksFor(seat: TeamSeat, seats: List<TeamSeat>): List<ModelRef> =
        if (seat.isRelay) emptyList() else writers(seats).filter { it != seat }.map { it.model }
}
