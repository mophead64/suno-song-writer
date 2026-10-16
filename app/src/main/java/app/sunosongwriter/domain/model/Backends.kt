package app.sunosongwriter.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AuthStyle {
    @SerialName("none") NONE,
    @SerialName("bearer") BEARER,

    /** The key as a bearer token and in `x-api-key`: Anthropic's model list needs the latter. */
    @SerialName("bearer_and_x_api_key") BEARER_AND_X_API_KEY,
}

/** How the app decides whether a backend's models cost money (spec 5.1, 5.2). */
@Serializable
enum class FreeAccess {
    /** Everything is free, e.g. local Ollama / LM Studio. */
    @SerialName("all") ALL,

    /** Free per model, from the provider's pricing data (OpenRouter `:free` models). */
    @SerialName("pricing") PRICING,

    /** Free when the key is on the provider's free tier; the user says whether it is. */
    @SerialName("key_tier") KEY_TIER,
}

@Serializable
enum class BackendKind {
    /** A server implementing OpenAI's chat API: called directly. */
    @SerialName("openai") OPENAI_COMPATIBLE,

    /** A consumer chat app (e.g. Claude's free plan). The user relays prompts and replies by hand (spec 5.2). */
    @SerialName("relay") RELAY,
}

/** Whether rate and daily limits are shared by all of a backend's models or apply to each model separately. */
@Serializable
enum class LimitScope {
    @SerialName("backend") BACKEND,
    @SerialName("model") MODEL,
}

@Serializable
data class Limits(
    val rpm: Int? = null,
    val rpd: Int? = null,
    val tpm: Int? = null,
) {
    fun overriddenBy(rule: ModelLimitRule) = Limits(
        rpm = rule.rpm ?: rpm,
        rpd = rule.rpd ?: rpd,
        tpm = rule.tpm ?: tpm,
    )
}

/** Overrides the backend's limits for models whose id contains [match]. */
@Serializable
data class ModelLimitRule(
    val match: String,
    val rpm: Int? = null,
    val rpd: Int? = null,
    val tpm: Int? = null,
)

data class Backend(
    val id: Long,
    val presetId: String?,
    val name: String,
    val baseUrl: String,
    val modelsUrl: String?,
    val auth: AuthStyle,
    val freeAccess: FreeAccess,
    val isFreeTier: Boolean,
    val limits: Limits,
    val modelLimits: List<ModelLimitRule>,
    val limitScope: LimitScope,
    val limitsFreeModelsOnly: Boolean,
    val dailyResetZone: String,
    val streamUsage: Boolean,
    val dailyRemainingHeader: Boolean,
    val extraHeaders: Map<String, String>,
    val keyHint: String?,
    val kind: BackendKind = BackendKind.OPENAI_COMPATIBLE,
    val relayPackage: String? = null,
) {
    val isNew: Boolean get() = id == 0L

    val isRelay: Boolean get() = kind == BackendKind.RELAY

    val needsKey: Boolean get() = auth != AuthStyle.NONE && !isRelay

    /** [pricedFree] as for [isFree]; it matters when [limitsFreeModelsOnly] is set. */
    fun limitsFor(modelId: String, pricedFree: Boolean? = null): Limits {
        if (limitsFreeModelsOnly && !isFree(modelId, pricedFree)) return Limits()
        return modelLimits.firstOrNull { modelId.contains(it.match, ignoreCase = true) }
            ?.let(limits::overriddenBy)
            ?: limits
    }

    fun isFree(model: ModelInfo): Boolean = isFree(model.modelId, model.pricedFree)

    /** [pricedFree] is null when the model isn't in the cached listing. */
    fun isFree(modelId: String, pricedFree: Boolean?): Boolean = when (freeAccess) {
        FreeAccess.ALL -> true
        FreeAccess.PRICING -> pricedFree ?: modelId.endsWith(":free")
        FreeAccess.KEY_TIER -> isFreeTier
    }

    /** Key used to group requests for pacing and daily counts. */
    fun limitKey(modelId: String): String = when (limitScope) {
        LimitScope.BACKEND -> "$id"
        LimitScope.MODEL -> "$id/$modelId"
    }

    /** Short label for lists: whether this backend is free, free per model, or paid. */
    val costLabel: String
        get() = if (isRelay) "Relay" else when (freeAccess) {
            FreeAccess.ALL -> "Free"
            FreeAccess.PRICING -> "Free models only"
            FreeAccess.KEY_TIER -> if (isFreeTier) "Free tier" else "Paid"
        }
}

data class ModelInfo(
    val backendId: Long,
    val modelId: String,
    val displayName: String,
    /** From pricing data, where the provider publishes it; null when unknown. */
    val pricedFree: Boolean?,
    val contextLength: Int?,
    val available: Boolean,
    val lastSeen: Long,
)

/** A specific model on a specific backend. */
@Serializable
data class ModelRef(val backendId: Long, val modelId: String)
