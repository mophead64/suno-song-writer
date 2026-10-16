package app.sunosongwriter.data.repo

import app.sunosongwriter.data.db.SnapshotDao
import app.sunosongwriter.data.db.SnapshotEntity
import app.sunosongwriter.domain.lint.SunoLint
import app.sunosongwriter.domain.model.Snapshot
import app.sunosongwriter.domain.model.SunoSettings
import app.sunosongwriter.domain.model.Version
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Snapshots of what was sent to Suno (spec 5.11). Insert-only. */
class SnapshotRepository(
    private val dao: SnapshotDao,
    private val json: Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun observeForSong(songId: Long): Flow<List<Snapshot>> = dao.observeForSong(songId).map { rows -> rows.map(::toDomain) }

    val sentSongIds: Flow<Set<Long>> = dao.observeSentSongIds().map { it.toSet() }

    val all: Flow<List<Snapshot>> = dao.observeAll().map { rows -> rows.map(::toDomain) }

    suspend fun get(id: Long): Snapshot? = dao.get(id)?.let(::toDomain)

    /** Freezes the version's clean Suno text and settings, exactly as the handoff screen copies them. */
    suspend fun markSent(songId: Long, version: Version, versionLabel: String): Long =
        dao.insert(
            SnapshotEntity(
                songId = songId,
                versionId = version.id,
                versionLabel = versionLabel,
                styleText = SunoLint.cleanStyle(version.content.style),
                lyricsText = SunoLint.cleanLyrics(version.content.lyrics),
                settingsJson = json.encodeToString(version.content.settings),
                sentAt = nowMillis(),
            ),
        )

    internal fun toDomain(e: SnapshotEntity) = Snapshot(
        id = e.id, songId = e.songId, versionId = e.versionId, versionLabel = e.versionLabel,
        styleText = e.styleText, lyricsText = e.lyricsText,
        settings = runCatching { json.decodeFromString<SunoSettings>(e.settingsJson) }.getOrDefault(SunoSettings()),
        sentAt = e.sentAt,
    )
}
