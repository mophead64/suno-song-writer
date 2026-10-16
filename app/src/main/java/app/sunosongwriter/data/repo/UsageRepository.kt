package app.sunosongwriter.data.repo

import app.sunosongwriter.data.db.PriceEntryEntity
import app.sunosongwriter.data.db.UsageDao
import app.sunosongwriter.data.db.UsageRecordEntity
import app.sunosongwriter.data.llm.LlmError
import app.sunosongwriter.data.llm.UsageEvent
import app.sunosongwriter.data.llm.UsageLedger
import app.sunosongwriter.data.llm.UsageTag
import app.sunosongwriter.domain.usage.CostEstimate
import app.sunosongwriter.domain.usage.CostSource
import app.sunosongwriter.domain.usage.Price
import app.sunosongwriter.domain.usage.SpendCheck
import app.sunosongwriter.domain.usage.SpendLimits
import app.sunosongwriter.domain.usage.UsageRecord
import app.sunosongwriter.domain.usage.UsageSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.ZoneId

/** Usage records, the price table and spend limits (spec 5.17). */
class UsageRepository(
    private val dao: UsageDao,
    private val limits: Flow<SpendLimits>,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : UsageLedger {
    fun observeSince(fromMillis: Long): Flow<List<UsageRecord>> = dao.observeSince(fromMillis).map { rows -> rows.map(::toDomain) }

    /** Reported plus estimated spend this calendar month. */
    fun observeMonthSpend(): Flow<Double> = dao.observeCostSince(UsageSummary.monthStartMillis(nowMillis(), zone()))

    fun observeSessionSpend(sessionId: Long): Flow<Double> = dao.observeSessionCost(sessionId)

    override suspend fun record(event: UsageEvent) {
        val input = event.inputTokens ?: 0
        val output = event.outputTokens ?: 0
        val price = if (event.reportedCostUsd == null && !event.isFree) dao.price(event.backend.id, event.modelId)?.toDomain() else null
        val (cost, source) = CostEstimate.of(event.reportedCostUsd, event.isFree, price, input, output)
        dao.insert(
            UsageRecordEntity(
                backendId = event.backend.id, backendName = event.backend.name, modelId = event.modelId,
                songId = event.tag.songId, sessionId = event.tag.sessionId, messageId = event.tag.messageId,
                inputTokens = input, outputTokens = output, costUsd = cost, costSource = source.name, at = nowMillis(),
            ),
        )
    }

    /** Paid requests stop at the monthly limit, and at the session limit for steps in a session. */
    override suspend fun spendBlock(tag: UsageTag): LlmError? {
        val l = limits.first()
        l.monthlyUsd?.let { limit ->
            val spent = dao.costSince(UsageSummary.monthStartMillis(nowMillis(), zone()))
            if (spent >= limit) {
                return LlmError.SpendLimit("${SpendCheck.format(spent)} spent this month, and the monthly limit is ${SpendCheck.format(limit)}. Raise it in Settings → Usage and limits, or use free models.")
            }
        }
        val sessionId = tag.sessionId ?: return null
        l.sessionUsd?.let { limit ->
            val spent = dao.sessionCost(sessionId)
            if (spent >= limit) {
                return LlmError.SpendLimit("This run has spent ${SpendCheck.format(spent)}, and the limit per run is ${SpendCheck.format(limit)}.")
            }
        }
        return null
    }

    // Price table

    val prices: Flow<List<Price>> = dao.observePrices().map { rows -> rows.map { it.toDomain() } }

    suspend fun savePrice(price: Price) =
        dao.upsertPrice(PriceEntryEntity(price.backendId, price.modelId.trim(), price.inputPerMTok, price.outputPerMTok, nowMillis()))

    suspend fun deletePrice(backendId: Long, modelId: String) = dao.deletePrice(backendId, modelId)

    private fun PriceEntryEntity.toDomain() = Price(backendId, modelId, inputPerMTok, outputPerMTok)

    private fun toDomain(e: UsageRecordEntity) = UsageRecord(
        id = e.id, backendId = e.backendId, backendName = e.backendName, modelId = e.modelId, songId = e.songId,
        sessionId = e.sessionId, inputTokens = e.inputTokens, outputTokens = e.outputTokens, costUsd = e.costUsd,
        source = CostSource.entries.firstOrNull { it.name == e.costSource } ?: CostSource.UNKNOWN, at = e.at,
    )
}
