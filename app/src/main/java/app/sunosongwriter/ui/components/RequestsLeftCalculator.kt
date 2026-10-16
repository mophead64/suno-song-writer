package app.sunosongwriter.ui.components

import app.sunosongwriter.data.db.RequestCountEntity
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.LimitScope
import app.sunosongwriter.domain.model.ModelRef
import app.sunosongwriter.domain.usage.DailyWindow
import app.sunosongwriter.domain.usage.QuotaTracker
import app.sunosongwriter.domain.usage.RequestsLeft

/** "Requests left today" for a model, combining provider reports with the app's own daily counts (spec 5.2). */
fun requestsLeft(
    ref: ModelRef,
    backends: List<Backend>,
    counts: List<RequestCountEntity>,
    quotas: QuotaTracker,
    nowMillis: Long,
    pricedFree: Boolean?,
): RequestsLeft? {
    val backend = backends.firstOrNull { it.id == ref.backendId } ?: return null
    val day = DailyWindow.dayKey(backend.dailyResetZone, nowMillis)
    val local = counts
        .filter { it.backendId == backend.id && it.day == day }
        .filter { backend.limitScope == LimitScope.BACKEND || it.modelId == ref.modelId }
        .sumOf { it.count }
    return quotas.requestsLeft(backend, ref.modelId, local, pricedFree)
}
