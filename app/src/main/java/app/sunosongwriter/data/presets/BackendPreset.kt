package app.sunosongwriter.data.presets

import app.sunosongwriter.domain.model.AuthStyle
import app.sunosongwriter.domain.model.BackendKind
import app.sunosongwriter.domain.model.FreeAccess
import app.sunosongwriter.domain.model.LimitScope
import app.sunosongwriter.domain.model.Limits
import app.sunosongwriter.domain.model.ModelLimitRule
import kotlinx.serialization.Serializable

@Serializable
data class PresetFile(
    val version: Int,
    val presets: List<BackendPreset>,
)

/** A ready-made backend definition from `assets/presets.json` (spec 5.2). */
@Serializable
data class BackendPreset(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val modelsUrl: String? = null,
    val auth: AuthStyle,
    val freeAccess: FreeAccess,
    val freeTierDefault: Boolean = false,
    val limits: Limits = Limits(),
    val modelLimits: List<ModelLimitRule> = emptyList(),
    val limitScope: LimitScope = LimitScope.BACKEND,
    /** The limits only apply to free models (OpenRouter's caps are for `:free` models). */
    val limitsFreeModelsOnly: Boolean = false,
    val dailyResetZone: String = "UTC",
    val streamUsage: Boolean = true,
    /** The provider's `x-ratelimit-remaining-requests` header counts requests per day (Groq). */
    val dailyRemainingHeader: Boolean = false,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Accepts `response_format: {"type": "json_object"}`, used for reviews and chair decisions. */
    val jsonMode: Boolean = false,
    val local: Boolean = false,
    val baseUrlHint: String? = null,
    val dataUseNote: String,
    val limitsNote: String? = null,
    val signupUrl: String? = null,
    val limitsUrl: String? = null,
    val verifiedOn: String,
    val unverified: Boolean = false,
    val kind: BackendKind = BackendKind.OPENAI_COMPATIBLE,
    val relayPackage: String? = null,
)
