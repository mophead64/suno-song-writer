package app.sunosongwriter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folder ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folder ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM folder")
    suspend fun maxSortOrder(): Int

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Query("UPDATE folder SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    /** Songs in the folder become unfiled (foreign key SET NULL). */
    @Query("DELETE FROM folder WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SongDao {
    @Query("SELECT * FROM song ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM song ORDER BY updatedAt DESC")
    suspend fun getAll(): List<SongEntity>

    @Query("SELECT * FROM song WHERE id = :id")
    fun observe(id: Long): Flow<SongEntity?>

    @Query("SELECT * FROM song WHERE id = :id")
    suspend fun get(id: Long): SongEntity?

    @Insert
    suspend fun insert(song: SongEntity): Long

    @Update
    suspend fun update(song: SongEntity)

    @Query("UPDATE song SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("UPDATE song SET folderId = :folderId WHERE id = :id")
    suspend fun move(id: Long, folderId: Long?)

    @Query("DELETE FROM song WHERE id = :id")
    suspend fun delete(id: Long)

    // Tags

    @Query("SELECT * FROM song_tag")
    fun observeAllTags(): Flow<List<SongTagEntity>>

    @Query("SELECT tag FROM song_tag WHERE songId = :songId ORDER BY tag COLLATE NOCASE")
    suspend fun tagsFor(songId: Long): List<String>

    @Query("SELECT tag FROM song_tag WHERE songId = :songId ORDER BY tag COLLATE NOCASE")
    fun observeTagsFor(songId: Long): Flow<List<String>>

    @Query("DELETE FROM song_tag WHERE songId = :songId")
    suspend fun clearTags(songId: Long)

    @Insert
    suspend fun insertTags(tags: List<SongTagEntity>)

    // Search index

    @Insert
    suspend fun insertSearch(row: SongSearchEntity)

    @Query("DELETE FROM song_search WHERE rowid = :songId")
    suspend fun deleteSearch(songId: Long)

    /** FTS tables don't raise the primary-key conflict `@Upsert` relies on, so replace by delete + insert. */
    @Transaction
    suspend fun replaceSearch(row: SongSearchEntity) {
        deleteSearch(row.songId)
        insertSearch(row)
    }

    @Query("SELECT rowid FROM song_search WHERE song_search MATCH :match")
    suspend fun search(match: String): List<Long>
}

@Dao
interface VersionDao {
    @Query("SELECT * FROM variant WHERE songId = :songId ORDER BY createdAt, id")
    fun observeVariants(songId: Long): Flow<List<VariantEntity>>

    @Query("SELECT * FROM variant WHERE songId = :songId ORDER BY createdAt, id")
    suspend fun variantsFor(songId: Long): List<VariantEntity>

    @Query("SELECT * FROM variant WHERE id = :id")
    suspend fun variant(id: Long): VariantEntity?

    @Query("SELECT songId, COUNT(*) AS n FROM variant GROUP BY songId")
    fun observeVariantCounts(): Flow<Map<@MapColumn("songId") Long, @MapColumn("n") Int>>

    @Insert
    suspend fun insertVariant(variant: VariantEntity): Long

    @Query("UPDATE variant SET name = :name WHERE id = :id")
    suspend fun renameVariant(id: Long, name: String)

    @Query("DELETE FROM variant WHERE id = :id")
    suspend fun deleteVariant(id: Long)

    @Query("SELECT v.* FROM version v JOIN variant r ON r.id = v.variantId WHERE r.songId = :songId ORDER BY v.createdAt, v.id")
    fun observeVersionsForSong(songId: Long): Flow<List<VersionEntity>>

    @Query("SELECT * FROM version WHERE variantId = :variantId ORDER BY number DESC LIMIT 1")
    suspend fun latest(variantId: Long): VersionEntity?

    @Query("SELECT * FROM version WHERE id = :id")
    suspend fun version(id: Long): VersionEntity?

    @Query("SELECT v.* FROM version v JOIN variant r ON r.id = v.variantId WHERE r.songId = :songId ORDER BY v.variantId, v.number")
    suspend fun versionsForSong(songId: Long): List<VersionEntity>

    /** The newest version of each of the song's variants. */
    @Query(
        """
        SELECT v.* FROM version v JOIN variant r ON r.id = v.variantId
        WHERE r.songId = :songId AND v.number = (SELECT MAX(number) FROM version WHERE variantId = v.variantId)
        """,
    )
    suspend fun latestPerVariant(songId: Long): List<VersionEntity>

    @Insert
    suspend fun insert(version: VersionEntity): Long

    /** Only used by import, to re-link parents after ids are reassigned. */
    @Query("UPDATE version SET parentVersionId = :parentId WHERE id = :id")
    suspend fun setParent(id: Long, parentId: Long)
}

@Dao
interface SnapshotDao {
    @Insert
    suspend fun insert(snapshot: SnapshotEntity): Long

    @Query("SELECT * FROM snapshot WHERE songId = :songId ORDER BY sentAt DESC, id DESC")
    fun observeForSong(songId: Long): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM snapshot WHERE songId = :songId ORDER BY sentAt, id")
    suspend fun forSong(songId: Long): List<SnapshotEntity>

    @Query("SELECT * FROM snapshot WHERE id = :id")
    suspend fun get(id: Long): SnapshotEntity?

    @Query("SELECT DISTINCT songId FROM snapshot")
    fun observeSentSongIds(): Flow<List<Long>>
}
