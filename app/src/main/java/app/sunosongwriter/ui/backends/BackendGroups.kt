package app.sunosongwriter.ui.backends

import app.sunosongwriter.data.llm.CleartextPolicy
import app.sunosongwriter.data.presets.BackendPreset
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.BackendKind
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How the backends page and the preset picker group backends, in display order. */
enum class BackendGroup(val label: String) {
    LOCAL("On your computer"),
    API("API providers"),
    RELAY("Chat apps (you relay by hand)"),
    ;

    companion object {
        fun of(backend: Backend): BackendGroup = when {
            backend.isRelay -> RELAY
            backend.baseUrl.toHttpUrlOrNull()?.host?.let(CleartextPolicy::isLocalHost) == true -> LOCAL
            else -> API
        }

        fun of(preset: BackendPreset): BackendGroup = when {
            preset.kind == BackendKind.RELAY -> RELAY
            preset.local -> LOCAL
            else -> API
        }
    }
}
