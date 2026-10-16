package app.sunosongwriter.data.repo

import app.sunosongwriter.data.db.BackendEntity
import app.sunosongwriter.data.db.ModelInfoEntity
import app.sunosongwriter.data.db.ParticipantEntity
import app.sunosongwriter.data.presets.BackendPreset
import app.sunosongwriter.domain.model.AuthStyle
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.BackendKind
import app.sunosongwriter.domain.model.FreeAccess
import app.sunosongwriter.domain.model.LimitScope
import app.sunosongwriter.domain.model.Limits
import app.sunosongwriter.domain.model.ModelInfo
import app.sunosongwriter.domain.model.ModelLimitRule
import app.sunosongwriter.domain.model.ModelRef
import app.sunosongwriter.domain.model.Participant
import app.sunosongwriter.domain.model.Role
import kotlinx.serialization.json.Json

internal class Mappers(private val json: Json) {
    fun toDomain(e: BackendEntity) = Backend(
        id = e.id,
        presetId = e.presetId,
        name = e.name,
        baseUrl = e.baseUrl,
        modelsUrl = e.modelsUrl,
        auth = enumOr(e.auth, AuthStyle.BEARER),
        freeAccess = enumOr(e.freeAccess, FreeAccess.KEY_TIER),
        isFreeTier = e.isFreeTier,
        limits = Limits(e.rpm, e.rpd, e.tpm),
        modelLimits = runCatching { json.decodeFromString<List<ModelLimitRule>>(e.modelLimitsJson) }.getOrDefault(emptyList()),
        limitScope = enumOr(e.limitScope, LimitScope.BACKEND),
        limitsFreeModelsOnly = e.limitsFreeModelsOnly,
        dailyResetZone = e.dailyResetZone,
        streamUsage = e.streamUsage,
        dailyRemainingHeader = e.dailyRemainingHeader,
        extraHeaders = runCatching { json.decodeFromString<Map<String, String>>(e.extraHeadersJson) }.getOrDefault(emptyMap()),
        keyHint = e.keyHint,
        kind = enumOr(e.kind, BackendKind.OPENAI_COMPATIBLE),
        relayPackage = e.relayPackage,
    )

    fun toEntity(b: Backend, createdAt: Long) = BackendEntity(
        id = b.id,
        presetId = b.presetId,
        name = b.name.trim(),
        baseUrl = b.baseUrl.trim().trimEnd('/'),
        modelsUrl = b.modelsUrl?.trim()?.takeIf { it.isNotEmpty() },
        auth = b.auth.name,
        freeAccess = b.freeAccess.name,
        isFreeTier = b.isFreeTier,
        rpm = b.limits.rpm,
        rpd = b.limits.rpd,
        tpm = b.limits.tpm,
        modelLimitsJson = json.encodeToString(b.modelLimits),
        limitScope = b.limitScope.name,
        limitsFreeModelsOnly = b.limitsFreeModelsOnly,
        dailyResetZone = b.dailyResetZone,
        streamUsage = b.streamUsage,
        dailyRemainingHeader = b.dailyRemainingHeader,
        extraHeadersJson = json.encodeToString(b.extraHeaders),
        keyHint = b.keyHint,
        createdAt = createdAt,
        kind = b.kind.name,
        relayPackage = b.relayPackage?.trim()?.takeIf { it.isNotEmpty() },
    )

    fun fromPreset(p: BackendPreset) = Backend(
        id = 0,
        presetId = p.id,
        name = p.name,
        baseUrl = p.baseUrl,
        modelsUrl = p.modelsUrl,
        auth = p.auth,
        freeAccess = p.freeAccess,
        isFreeTier = p.freeTierDefault,
        limits = p.limits,
        modelLimits = p.modelLimits,
        limitScope = p.limitScope,
        limitsFreeModelsOnly = p.limitsFreeModelsOnly,
        dailyResetZone = p.dailyResetZone,
        streamUsage = p.streamUsage,
        dailyRemainingHeader = p.dailyRemainingHeader,
        extraHeaders = p.extraHeaders,
        keyHint = null,
        kind = p.kind,
        relayPackage = p.relayPackage,
    )

    fun toDomain(e: ModelInfoEntity) = ModelInfo(
        backendId = e.backendId,
        modelId = e.modelId,
        displayName = e.displayName,
        pricedFree = e.pricedFree,
        contextLength = e.contextLength,
        available = e.available,
        lastSeen = e.lastSeen,
    )

    fun toDomain(e: ParticipantEntity) = Participant(
        id = e.id,
        name = e.name,
        avatar = e.avatar,
        defaultRole = e.defaultRole?.let { name -> Role.entries.firstOrNull { it.name == name } },
        model = if (e.backendId != null && e.modelId != null) ModelRef(e.backendId, e.modelId) else null,
        fallbacks = runCatching { json.decodeFromString<List<ModelRef>>(e.fallbacksJson) }.getOrDefault(emptyList()),
    )

    fun toEntity(p: Participant, createdAt: Long) = ParticipantEntity(
        id = p.id,
        name = p.name.trim(),
        avatar = p.avatar.trim(),
        defaultRole = p.defaultRole?.name,
        backendId = p.model?.backendId,
        modelId = p.model?.modelId,
        fallbacksJson = json.encodeToString(p.fallbacks),
        createdAt = createdAt,
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
