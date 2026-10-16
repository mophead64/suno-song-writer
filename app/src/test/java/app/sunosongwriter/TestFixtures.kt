package app.sunosongwriter

import app.sunosongwriter.domain.model.AuthStyle
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.FreeAccess
import app.sunosongwriter.domain.model.LimitScope
import app.sunosongwriter.domain.model.Limits

fun testBackend(
    id: Long = 1,
    freeAccess: FreeAccess = FreeAccess.KEY_TIER,
    isFreeTier: Boolean = true,
    limits: Limits = Limits(),
    limitScope: LimitScope = LimitScope.MODEL,
    auth: AuthStyle = AuthStyle.BEARER,
    dailyRemainingHeader: Boolean = false,
) = Backend(
    id = id,
    presetId = null,
    name = "Test",
    baseUrl = "https://example.com/v1",
    modelsUrl = null,
    auth = auth,
    freeAccess = freeAccess,
    isFreeTier = isFreeTier,
    limits = limits,
    modelLimits = emptyList(),
    limitScope = limitScope,
    limitsFreeModelsOnly = false,
    dailyResetZone = "UTC",
    streamUsage = true,
    dailyRemainingHeader = dailyRemainingHeader,
    extraHeaders = emptyMap(),
    keyHint = null,
)
