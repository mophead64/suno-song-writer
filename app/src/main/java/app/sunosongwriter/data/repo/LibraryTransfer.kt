package app.sunosongwriter.data.repo

import androidx.room.withTransaction
import app.sunosongwriter.data.db.AppDatabase
import app.sunosongwriter.data.db.FolderEntity
import app.sunosongwriter.data.db.SnapshotEntity
import app.sunosongwriter.data.db.SongEntity
import app.sunosongwriter.data.db.SongTagEntity
import app.sunosongwriter.data.db.VariantEntity
import app.sunosongwriter.domain.model.Author
import app.sunosongwriter.domain.model.AuthorType
import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StylePrompt
import app.sunosongwriter.domain.model.SunoSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LibraryExport(
    val format: String = FORMAT,
    val formatVersion: Int = 1,
    val exportedAt: Long,
    val folders: List<FolderDto>,
    val songs: List<SongDto>,
) {
    companion object {
        const val FORMAT = "song-writer-library"
    }
}

@Serializable
data class FolderDto(val id: Long, val name: String)

@Serializable
data class SongDto(
    val title: String,
    val artist: String,
    val concept: String,
    val tags: List<String>,
    val folderId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val variants: List<VariantDto>,
    /** What was sent to Suno (spec 5.11). Absent in exports from before iteration 5. */
    val snapshots: List<SnapshotDto> = emptyList(),
)

@Serializable
data class SnapshotDto(
    val versionId: Long,
    val versionLabel: String,
    val styleText: String,
    val lyricsText: String,
    val settings: SunoSettings,
    val sentAt: Long,
)

@Serializable
data class VariantDto(val name: String, val createdAt: Long, val versions: List<VersionDto>)

@Serializable
data class VersionDto(
    val id: Long,
    val number: Int,
    val parentVersionId: Long?,
    val style: StylePrompt,
    val lyrics: String,
    val settings: SunoSettings,
    val authorType: AuthorType,
    val authorLabel: String,
    val step: String?,
    val changeNote: String,
    val createdAt: Long,
)

data class ImportResult(val songsAdded: Int, val songsSkipped: Int, val foldersAdded: Int)

/**
 * JSON export and import of the whole library (spec section 7). Contains songs, folders, variants and
 * versions only: never API keys or backend settings. Participant links are dropped; author labels stay.
 */
class LibraryTransfer(
    private val db: AppDatabase,
    private val songs: SongRepository,
    private val json: Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val pretty = Json(json) { prettyPrint = true }

    suspend fun export(): String {
        val folders = db.folderDao().getAll().map { FolderDto(it.id, it.name) }
        val songDtos = db.songDao().getAll().map { song ->
            val versions = db.versionDao().versionsForSong(song.id).map(songs::toDomain)
            SongDto(
                title = song.title,
                artist = song.artist,
                concept = song.concept,
                tags = db.songDao().tagsFor(song.id),
                folderId = song.folderId,
                createdAt = song.createdAt,
                updatedAt = song.updatedAt,
                variants = db.versionDao().variantsFor(song.id).map { variant ->
                    VariantDto(
                        name = variant.name,
                        createdAt = variant.createdAt,
                        versions = versions.filter { it.variantId == variant.id }.map { v ->
                            VersionDto(
                                v.id, v.number, v.parentVersionId, v.content.style, v.content.lyrics, v.content.settings,
                                v.author.type, v.author.label, v.step, v.changeNote, v.createdAt,
                            )
                        },
                    )
                },
                snapshots = db.snapshotDao().forSong(song.id).map {
                    SnapshotDto(it.versionId, it.versionLabel, it.styleText, it.lyricsText, json.decodeFromString(it.settingsJson), it.sentAt)
                },
            )
        }
        return pretty.encodeToString(LibraryExport(exportedAt = nowMillis(), folders = folders, songs = songDtos))
    }

    /**
     * Adds the exported songs to the library. Folders are matched by name. A song with the same title,
     * artist and creation time as an existing one is skipped, so importing the same file twice is harmless.
     */
    suspend fun import(text: String): ImportResult {
        val export = json.decodeFromString<LibraryExport>(text)
        require(export.format == LibraryExport.FORMAT) { "This isn't a Song Writer library export." }
        return db.withTransaction {
            val folderDao = db.folderDao()
            val songDao = db.songDao()
            val versionDao = db.versionDao()

            val existingFolders = folderDao.getAll().associateBy { it.name.lowercase() }.toMutableMap()
            var foldersAdded = 0
            val folderIds = export.folders.associate { dto ->
                val match = existingFolders[dto.name.lowercase()]
                dto.id to (match?.id ?: folderDao.insert(FolderEntity(name = dto.name, sortOrder = folderDao.maxSortOrder() + 1, createdAt = nowMillis())).also {
                    foldersAdded++
                    existingFolders[dto.name.lowercase()] = FolderEntity(it, dto.name, 0, 0)
                })
            }

            val existingKeys = songDao.getAll().mapTo(HashSet()) { Triple(it.title, it.artist, it.createdAt) }
            var added = 0
            var skipped = 0
            for (dto in export.songs) {
                if (!existingKeys.add(Triple(dto.title, dto.artist, dto.createdAt))) {
                    skipped++
                    continue
                }
                val songId = songDao.insert(
                    SongEntity(folderId = dto.folderId?.let(folderIds::get), title = dto.title, artist = dto.artist, concept = dto.concept, createdAt = dto.createdAt, updatedAt = dto.updatedAt),
                )
                songDao.insertTags(dto.tags.distinctBy { it.lowercase() }.map { SongTagEntity(songId, it) })
                val newIds = HashMap<Long, Long>()
                val pendingParents = mutableListOf<Pair<Long, Long>>() // new version id → old parent id
                for (variant in dto.variants) {
                    val variantId = versionDao.insertVariant(VariantEntity(songId = songId, name = variant.name, createdAt = variant.createdAt))
                    for (v in variant.versions.sortedBy { it.number }) {
                        val row = songs.entity(
                            variantId, v.number, parent = null,
                            content = SongContent(v.style, v.lyrics, v.settings),
                            author = Author(v.authorType, v.authorLabel), step = v.step, note = v.changeNote, at = v.createdAt,
                        )
                        val id = versionDao.insert(row)
                        newIds[v.id] = id
                        v.parentVersionId?.let { pendingParents += id to it }
                    }
                }
                for ((id, oldParent) in pendingParents) {
                    newIds[oldParent]?.let { versionDao.setParent(id, it) }
                }
                for (s in dto.snapshots) {
                    db.snapshotDao().insert(
                        SnapshotEntity(
                            songId = songId, versionId = newIds[s.versionId] ?: 0, versionLabel = s.versionLabel,
                            styleText = s.styleText, lyricsText = s.lyricsText, settingsJson = json.encodeToString(s.settings), sentAt = s.sentAt,
                        ),
                    )
                }
                songs.reindex(songId)
                added++
            }
            ImportResult(added, skipped, foldersAdded)
        }
    }
}
