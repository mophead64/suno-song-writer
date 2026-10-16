package app.sunosongwriter.data.repo

import androidx.room.withTransaction
import app.sunosongwriter.data.db.AppDatabase
import app.sunosongwriter.data.db.FolderEntity
import app.sunosongwriter.data.db.IssueFixEntity
import app.sunosongwriter.data.db.RecipeEntity
import app.sunosongwriter.data.db.SnapshotEntity
import app.sunosongwriter.data.db.SongEntity
import app.sunosongwriter.data.db.SongTagEntity
import app.sunosongwriter.data.db.SongWinnerEntity
import app.sunosongwriter.data.db.SunoResultEntity
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
    /** Absent in exports from before iteration 6. */
    val recipes: List<RecipeDto> = emptyList(),
    /** Edited built-in fixes and custom issue tags. */
    val issueFixes: List<IssueFixDto> = emptyList(),
) {
    companion object {
        const val FORMAT = "song-writer-library"
    }
}

@Serializable
data class FolderDto(val id: Long, val name: String)

@Serializable
data class SongDto(
    /** Only for linking recipes on import. */
    val id: Long = 0,
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
    /** Which variant won (spec 5.12). */
    val winner: WinnerDto? = null,
)

@Serializable
data class WinnerDto(val variantName: String, val snapshotId: Long?)

@Serializable
data class SnapshotDto(
    /** Only for linking takes, winners and recipes on import. */
    val id: Long = 0,
    val versionId: Long,
    val versionLabel: String,
    val styleText: String,
    val lyricsText: String,
    val settings: SunoSettings,
    val sentAt: Long,
    val takes: List<TakeDto> = emptyList(),
)

@Serializable
data class TakeDto(val index: Int, val link: String?, val rating: Int?, val notes: String, val issues: List<String>, val createdAt: Long)

@Serializable
data class RecipeDto(
    val id: Long,
    val name: String,
    val snapshotId: Long?,
    val songId: Long?,
    val parentRecipeId: Long?,
    val songTitle: String,
    val artist: String,
    val tags: List<String>,
    val styleText: String,
    val lyricsText: String,
    val settings: SunoSettings,
    val ratingAtSave: Int?,
    val createdAt: Long,
)

@Serializable
data class IssueFixDto(val tag: String, val fixText: String, val builtIn: Boolean)

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
 * JSON export and import of the whole library (spec section 7): songs, folders, variants, versions,
 * snapshots and their results, winners, recipes and the issue-fix table. Never API keys or backend
 * settings. Participant links are dropped; author labels stay.
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
        val resultDao = db.resultDao()
        val songDtos = db.songDao().getAll().map { song ->
            val versions = db.versionDao().versionsForSong(song.id).map(songs::toDomain)
            SongDto(
                id = song.id,
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
                snapshots = db.snapshotDao().forSong(song.id).map { s ->
                    SnapshotDto(
                        s.id, s.versionId, s.versionLabel, s.styleText, s.lyricsText, json.decodeFromString(s.settingsJson), s.sentAt,
                        takes = resultDao.forSnapshot(s.id).map { r ->
                            TakeDto(r.result.takeIndex, r.result.link, r.result.rating, r.result.notes, r.issues.map { it.tag }, r.result.createdAt)
                        },
                    )
                },
                winner = resultDao.winner(song.id)?.let { WinnerDto(it.variantName, it.snapshotId) },
            )
        }
        val recipes = db.recipeDao().getAll().map { r ->
            RecipeDto(
                r.id, r.name, r.snapshotId, r.songId, r.parentRecipeId, r.songTitle, r.artist, json.decodeFromString(r.tagsJson),
                r.styleText, r.lyricsText, json.decodeFromString(r.settingsJson), r.ratingAtSave, r.createdAt,
            )
        }
        val fixes = resultDao.fixes().map { IssueFixDto(it.tag, it.fixText, it.builtIn) }
        return pretty.encodeToString(LibraryExport(exportedAt = nowMillis(), folders = folders, songs = songDtos, recipes = recipes, issueFixes = fixes))
    }

    /**
     * Adds the exported songs to the library. Folders are matched by name. A song with the same title,
     * artist and creation time as an existing one is skipped, so importing the same file twice is harmless;
     * so is a recipe with the same name and creation time. Custom fixes only fill tags not already set.
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

            val resultDao = db.resultDao()
            val existingSongs = songDao.getAll().associate { Triple(it.title, it.artist, it.createdAt) to it.id }
            val existingKeys = existingSongs.keys.toHashSet()
            val songIds = HashMap<Long, Long>()
            val snapshotIds = HashMap<Long, Long>()
            var added = 0
            var skipped = 0
            for (dto in export.songs) {
                val key = Triple(dto.title, dto.artist, dto.createdAt)
                if (!existingKeys.add(key)) {
                    existingSongs[key]?.let { songIds[dto.id] = it }
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
                songIds[dto.id] = songId
                for (s in dto.snapshots) {
                    val snapshotId = db.snapshotDao().insert(
                        SnapshotEntity(
                            songId = songId, versionId = newIds[s.versionId] ?: 0, versionLabel = s.versionLabel,
                            styleText = s.styleText, lyricsText = s.lyricsText, settingsJson = json.encodeToString(s.settings), sentAt = s.sentAt,
                        ),
                    )
                    snapshotIds[s.id] = snapshotId
                    for (t in s.takes) {
                        val resultId = resultDao.insert(
                            SunoResultEntity(
                                snapshotId = snapshotId, takeIndex = t.index, link = t.link, rating = t.rating, notes = t.notes,
                                createdAt = t.createdAt, updatedAt = t.createdAt,
                            ),
                        )
                        resultDao.replaceIssues(resultId, t.issues)
                    }
                }
                dto.winner?.let { w -> resultDao.setWinner(SongWinnerEntity(songId, w.variantName, w.snapshotId?.let(snapshotIds::get), nowMillis())) }
                songs.reindex(songId)
                added++
            }
            importRecipes(export.recipes, songIds, snapshotIds)
            val existingFixes = resultDao.fixes().map { it.tag }.toSet()
            export.issueFixes.filter { it.tag !in existingFixes }.forEach { f ->
                resultDao.upsertFix(IssueFixEntity(f.tag, f.fixText, f.builtIn, nowMillis()))
            }
            ImportResult(added, skipped, foldersAdded)
        }
    }

    /** Oldest first, so an adapted recipe's original already has its new id. */
    private suspend fun importRecipes(recipes: List<RecipeDto>, songIds: Map<Long, Long>, snapshotIds: Map<Long, Long>) {
        val dao = db.recipeDao()
        val existing = dao.getAll().mapTo(HashSet()) { it.name to it.createdAt }
        val recipeIds = HashMap<Long, Long>()
        for (r in recipes.sortedBy { it.createdAt }) {
            if (!existing.add(r.name to r.createdAt)) continue
            recipeIds[r.id] = dao.insert(
                RecipeEntity(
                    name = r.name, snapshotId = r.snapshotId?.let(snapshotIds::get), songId = r.songId?.let(songIds::get),
                    parentRecipeId = r.parentRecipeId?.let(recipeIds::get), songTitle = r.songTitle, artist = r.artist,
                    tagsJson = json.encodeToString(r.tags), styleText = r.styleText, lyricsText = r.lyricsText,
                    settingsJson = json.encodeToString(r.settings), ratingAtSave = r.ratingAtSave, createdAt = r.createdAt,
                ),
            )
        }
    }
}
