package app.sunosongwriter.data.repo

import androidx.room.withTransaction
import app.sunosongwriter.data.db.AppDatabase
import app.sunosongwriter.data.db.FolderEntity
import app.sunosongwriter.data.db.SongEntity
import app.sunosongwriter.data.db.SongSearchEntity
import app.sunosongwriter.data.db.SongTagEntity
import app.sunosongwriter.data.db.VariantEntity
import app.sunosongwriter.data.db.VersionEntity
import app.sunosongwriter.domain.model.Author
import app.sunosongwriter.domain.model.AuthorType
import app.sunosongwriter.domain.model.Folder
import app.sunosongwriter.domain.model.SearchQuery
import app.sunosongwriter.domain.model.Song
import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StylePrompt
import app.sunosongwriter.domain.model.SunoSettings
import app.sunosongwriter.domain.model.Variant
import app.sunosongwriter.domain.model.Version
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Songs, folders, variants and versions (spec 4, 5.3, 5.7). Versions are only ever inserted. */
class SongRepository(
    private val db: AppDatabase,
    private val json: Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val folderDao = db.folderDao()
    private val songDao = db.songDao()
    private val versionDao = db.versionDao()

    val folders: Flow<List<Folder>> = folderDao.observeAll().map { list -> list.map { Folder(it.id, it.name) } }

    val songs: Flow<List<Song>> = combine(songDao.observeAll(), songDao.observeAllTags()) { songs, tags ->
        val bySong = tags.groupBy({ it.songId }, { it.tag })
        songs.map { it.toDomain(bySong[it.id].orEmpty().sortedBy(String::lowercase)) }
    }

    fun observeSong(id: Long): Flow<Song?> =
        combine(songDao.observe(id), songDao.observeTagsFor(id)) { song, tags -> song?.toDomain(tags) }

    fun observeVariants(songId: Long): Flow<List<Variant>> =
        versionDao.observeVariants(songId).map { list -> list.map { Variant(it.id, it.songId, it.name, it.createdAt) } }

    /** Every version of the song, oldest first, across all variants. */
    fun observeVersions(songId: Long): Flow<List<Version>> =
        versionDao.observeVersionsForSong(songId).map { list -> list.map(::toDomain) }

    /** Song ids matching a full-text query, or null when the query has nothing searchable. */
    suspend fun search(query: String): Set<Long>? {
        val match = SearchQuery.toFts(query) ?: return null
        return songDao.search(match).toSet()
    }

    // Folders

    suspend fun createFolder(name: String): Long =
        folderDao.insert(FolderEntity(name = name.trim(), sortOrder = folderDao.maxSortOrder() + 1, createdAt = nowMillis()))

    suspend fun renameFolder(id: Long, name: String) = folderDao.rename(id, name.trim())

    suspend fun deleteFolder(id: Long) = folderDao.delete(id)

    // Songs

    /** Creates the song with a `Draft` variant holding an empty first version. */
    suspend fun createSong(title: String, artist: String, concept: String, tags: List<String>, folderId: Long?): Long =
        db.withTransaction {
            val now = nowMillis()
            val songId = songDao.insert(SongEntity(folderId = folderId, title = title.trim(), artist = artist.trim(), concept = concept.trim(), createdAt = now, updatedAt = now))
            songDao.insertTags(normaliseTags(tags).map { SongTagEntity(songId, it) })
            val variantId = versionDao.insertVariant(VariantEntity(songId = songId, name = DEFAULT_VARIANT, createdAt = now))
            versionDao.insert(entity(variantId, number = 1, parent = null, content = SongContent(), author = Author.USER, step = null, note = "Created", at = now))
            reindex(songId)
            songId
        }

    suspend fun updateSong(song: Song) = db.withTransaction {
        val existing = songDao.get(song.id) ?: return@withTransaction
        songDao.update(existing.copy(folderId = song.folderId, title = song.title.trim(), artist = song.artist.trim(), concept = song.concept.trim(), updatedAt = nowMillis()))
        songDao.clearTags(song.id)
        songDao.insertTags(normaliseTags(song.tags).map { SongTagEntity(song.id, it) })
        reindex(song.id)
    }

    suspend fun moveSong(id: Long, folderId: Long?) = songDao.move(id, folderId)

    suspend fun deleteSong(id: Long) = db.withTransaction {
        songDao.delete(id)
        songDao.deleteSearch(id)
    }

    // Variants

    /** A new variant, starting from a copy of [fromVersionId] (or empty). */
    suspend fun createVariant(songId: Long, name: String, fromVersionId: Long?): Long = db.withTransaction {
        val now = nowMillis()
        val variantId = versionDao.insertVariant(VariantEntity(songId = songId, name = name.trim(), createdAt = now))
        val from = fromVersionId?.let { versionDao.version(it) }
        val note = from?.let { "Started from ${versionDao.variant(it.variantId)?.name ?: "another variant"} v${it.number}" } ?: "Created"
        val content = from?.let { toDomain(it).content } ?: SongContent()
        versionDao.insert(entity(variantId, number = 1, parent = from?.id, content = content, author = Author.USER, step = null, note = note, at = now))
        songDao.touch(songId, now)
        reindex(songId)
        variantId
    }

    suspend fun renameVariant(id: Long, name: String) = versionDao.renameVariant(id, name.trim())

    /** Deletes a variant and its versions; refuses to delete a song's last variant. */
    suspend fun deleteVariant(id: Long): Boolean = db.withTransaction {
        val variant = versionDao.variant(id) ?: return@withTransaction false
        if (versionDao.variantsFor(variant.songId).size <= 1) return@withTransaction false
        versionDao.deleteVariant(id)
        reindex(variant.songId)
        true
    }

    // Versions

    suspend fun latest(variantId: Long): Version? = versionDao.latest(variantId)?.let(::toDomain)

    /** Saves [content] as the variant's next version. Returns null if nothing changed. */
    suspend fun saveVersion(variantId: Long, content: SongContent, author: Author, changeNote: String, step: String? = null): Version? =
        db.withTransaction {
            val variant = versionDao.variant(variantId) ?: return@withTransaction null
            val latest = versionDao.latest(variantId)
            if (latest != null && toDomain(latest).content == content) return@withTransaction null
            val now = nowMillis()
            val row = entity(variantId, (latest?.number ?: 0) + 1, latest?.id, content, author, step, changeNote.trim(), now)
            val id = versionDao.insert(row)
            songDao.touch(variant.songId, now)
            reindex(variant.songId)
            toDomain(row.copy(id = id))
        }

    /** Restores an old version by saving a copy of it as the newest version of its variant (spec 5.7). */
    suspend fun restore(versionId: Long): Version? {
        val old = versionDao.version(versionId)?.let(::toDomain) ?: return null
        return saveVersion(old.variantId, old.content, Author.USER, "Restored v${old.number}")
    }

    internal suspend fun reindex(songId: Long) {
        val song = songDao.get(songId) ?: return
        val lyrics = versionDao.latestPerVariant(songId).joinToString("\n") { it.lyrics }
        songDao.replaceSearch(
            SongSearchEntity(songId, song.title, song.artist, songDao.tagsFor(songId).joinToString(" "), song.concept, lyrics),
        )
    }

    private fun normaliseTags(tags: List<String>): List<String> =
        tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    private fun SongEntity.toDomain(tags: List<String>) = Song(id, folderId, title, artist, concept, tags, createdAt, updatedAt)

    internal fun toDomain(e: VersionEntity) = Version(
        id = e.id,
        variantId = e.variantId,
        number = e.number,
        parentVersionId = e.parentVersionId,
        content = SongContent(
            style = runCatching { json.decodeFromString<StylePrompt>(e.styleJson) }.getOrDefault(StylePrompt()),
            lyrics = e.lyrics,
            settings = runCatching { json.decodeFromString<SunoSettings>(e.settingsJson) }.getOrDefault(SunoSettings()),
        ),
        author = Author(
            type = AuthorType.entries.firstOrNull { it.name == e.authorType } ?: AuthorType.USER,
            label = e.authorLabel,
            participantId = e.participantId,
        ),
        step = e.step,
        changeNote = e.changeNote,
        createdAt = e.createdAt,
    )

    internal fun entity(variantId: Long, number: Int, parent: Long?, content: SongContent, author: Author, step: String?, note: String, at: Long) =
        VersionEntity(
            variantId = variantId,
            number = number,
            parentVersionId = parent,
            styleJson = json.encodeToString(content.style),
            lyrics = content.lyrics,
            settingsJson = json.encodeToString(content.settings),
            authorType = author.type.name,
            authorLabel = author.label,
            participantId = author.participantId,
            step = step,
            changeNote = note,
            createdAt = at,
        )

    companion object {
        const val DEFAULT_VARIANT = "Draft"
    }
}
