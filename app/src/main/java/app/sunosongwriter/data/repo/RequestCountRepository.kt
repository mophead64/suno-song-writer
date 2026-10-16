package app.sunosongwriter.data.repo

import app.sunosongwriter.data.db.RequestCountDao
import app.sunosongwriter.data.db.RequestCountEntity
import app.sunosongwriter.data.llm.RequestCounter
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.LimitScope
import app.sunosongwriter.domain.usage.DailyWindow
import kotlinx.coroutines.flow.Flow

/** Local count of requests per day, the fallback when a provider doesn't report remaining quota (spec 5.2). */
class RequestCountRepository(
    private val dao: RequestCountDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : RequestCounter {
    override suspend fun countToday(backend: Backend, modelId: String): Int {
        val day = DailyWindow.dayKey(backend.dailyResetZone, nowMillis())
        return when (backend.limitScope) {
            LimitScope.BACKEND -> dao.countForBackend(backend.id, day)
            LimitScope.MODEL -> dao.countForModel(backend.id, modelId, day) ?: 0
        }
    }

    override suspend fun increment(backend: Backend, modelId: String) {
        dao.increment(backend.id, modelId, DailyWindow.dayKey(backend.dailyResetZone, nowMillis()))
    }

    override fun nextResetMillis(backend: Backend): Long = DailyWindow.nextReset(backend.dailyResetZone, nowMillis())

    /** Rows from yesterday (UTC) onwards: enough to cover "today" in any reset time zone. */
    fun observeRecent(): Flow<List<RequestCountEntity>> =
        dao.observeSince(DailyWindow.dayKey("UTC", nowMillis() - DAY_MILLIS))

    suspend fun pruneOlderThan(days: Int) {
        dao.deleteBefore(DailyWindow.dayKey("UTC", nowMillis() - days * DAY_MILLIS))
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
