package app.sunosongwriter.data.repo

import androidx.room.withTransaction
import app.sunosongwriter.data.db.AppDatabase
import app.sunosongwriter.data.db.IssueFixEntity
import app.sunosongwriter.data.db.ResultWithIssues
import app.sunosongwriter.data.db.SongWinnerEntity
import app.sunosongwriter.data.db.SunoResultEntity
import app.sunosongwriter.domain.model.Snapshot
import app.sunosongwriter.domain.results.BuiltInIssues
import app.sunosongwriter.domain.results.FixContext
import app.sunosongwriter.domain.results.IssueCatalog
import app.sunosongwriter.domain.results.IssueFix
import app.sunosongwriter.domain.results.ResultsBrief
import app.sunosongwriter.domain.results.SongWinner
import app.sunosongwriter.domain.results.StoredFix
import app.sunosongwriter.domain.results.Take
import app.sunosongwriter.domain.results.variantName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The results log (spec 5.12), winners, and the editable tag → fix table (5.13). */
class ResultRepository(
    private val db: AppDatabase,
    private val snapshots: SnapshotRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.resultDao()

    fun observeTakes(snapshotId: Long): Flow<List<Take>> = dao.observeForSnapshot(snapshotId).map { it.map(::toDomain) }

    fun observeTakesForSong(songId: Long): Flow<List<Take>> = dao.observeForSong(songId).map { it.map(::toDomain) }

    val allTakes: Flow<List<Take>> = dao.observeAll().map { it.map(::toDomain) }

    val songIdsWithResults: Flow<Set<Long>> = dao.observeSongIdsWithResults().map { it.toSet() }

    suspend fun takes(snapshotId: Long): List<Take> = dao.forSnapshot(snapshotId).map(::toDomain)

    /** Logs a take; it's numbered after the snapshot's existing takes. */
    suspend fun addTake(snapshotId: Long, link: String?, rating: Int?, notes: String, issues: List<String>): Long = db.withTransaction {
        val now = nowMillis()
        val id = dao.insert(
            SunoResultEntity(
                snapshotId = snapshotId, takeIndex = dao.maxTakeIndex(snapshotId) + 1, link = link?.trim()?.ifEmpty { null },
                rating = rating?.coerceIn(1, 5), notes = notes.trim(), createdAt = now, updatedAt = now,
            ),
        )
        dao.replaceIssues(id, issues)
        id
    }

    /** Results are the user's notes, so unlike snapshots they can be corrected. */
    suspend fun updateTake(id: Long, link: String?, rating: Int?, notes: String, issues: List<String>) = db.withTransaction {
        val row = dao.get(id) ?: return@withTransaction
        dao.update(row.copy(link = link?.trim()?.ifEmpty { null }, rating = rating?.coerceIn(1, 5), notes = notes.trim(), updatedAt = nowMillis()))
        dao.replaceIssues(id, issues)
    }

    suspend fun deleteTake(id: Long) = dao.delete(id)

    // Winners

    fun observeWinner(songId: Long): Flow<SongWinner?> = dao.observeWinner(songId).map { it?.toDomain() }

    /** Song id → winning variant name. */
    val winners: Flow<Map<Long, String>> = dao.observeWinners().map { list -> list.associate { it.songId to it.variantName } }

    /** The snapshot's variant won for its song. Replaces any earlier winner. */
    suspend fun markWinner(snapshot: Snapshot) =
        dao.setWinner(SongWinnerEntity(snapshot.songId, snapshot.variantName, snapshot.id, nowMillis()))

    suspend fun clearWinner(songId: Long) = dao.clearWinner(songId)

    // Issue fixes

    val catalog: Flow<List<IssueFix>> = dao.observeFixes().map { rows -> IssueCatalog.merge(rows.map { StoredFix(it.tag, it.fixText, it.builtIn) }) }

    suspend fun catalog(): List<IssueFix> = IssueCatalog.merge(dao.fixes().map { StoredFix(it.tag, it.fixText, it.builtIn) })

    /** Saves a fix's text. A built-in set back to the guide's text stops being an override. */
    suspend fun saveFix(tag: String, fixText: String) {
        val builtIn = BuiltInIssues.find(tag)
        when {
            builtIn != null && fixText.trim() == builtIn.fixText -> dao.deleteFix(tag)
            else -> dao.upsertFix(IssueFixEntity(tag, fixText.trim(), builtIn = builtIn != null, updatedAt = nowMillis()))
        }
    }

    /** Back to the guide's fix. Only for built-ins. */
    suspend fun resetFix(tag: String) {
        if (BuiltInIssues.find(tag) != null) dao.deleteFix(tag)
    }

    /** Adds a custom tag; returns its tag, or null if the name is empty or already taken. */
    suspend fun addCustomTag(name: String, fixText: String = ""): String? {
        val tag = IssueCatalog.customTagName(name, catalog()) ?: return null
        dao.upsertFix(IssueFixEntity(tag, fixText.trim(), builtIn = false, updatedAt = nowMillis()))
        return tag
    }

    /** Removes a custom tag from the list. Takes already tagged with it keep the tag. */
    suspend fun deleteCustomTag(tag: String) {
        if (BuiltInIssues.find(tag) == null) dao.deleteFix(tag)
    }

    /** Everything Fix from results sends about a snapshot (spec 5.13), or null if it doesn't exist. */
    suspend fun fixContext(snapshotId: Long): FixContext? {
        val snapshot = snapshots.get(snapshotId) ?: return null
        val takes = takes(snapshotId)
        val catalog = catalog()
        return FixContext(
            snapshot = snapshot,
            results = ResultsBrief.describe(takes, catalog),
            issues = ResultsBrief.issueTags(takes, catalog).map { IssueCatalog.label(it, catalog) },
        )
    }

    private fun toDomain(r: ResultWithIssues) = Take(
        id = r.result.id, snapshotId = r.result.snapshotId, index = r.result.takeIndex, link = r.result.link,
        rating = r.result.rating, notes = r.result.notes, issues = r.issues.map { it.tag }.sorted(), createdAt = r.result.createdAt,
    )

    private fun SongWinnerEntity.toDomain() = SongWinner(songId, variantName, snapshotId)
}
