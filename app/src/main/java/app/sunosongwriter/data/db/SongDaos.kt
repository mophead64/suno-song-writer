package app.sunosongwriter.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
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

    @Query("SELECT * FROM snapshot ORDER BY sentAt, id")
    fun observeAll(): Flow<List<SnapshotEntity>>
}

/** A take with its issue tags. */
data class ResultWithIssues(
    @Embedded val result: SunoResultEntity,
    @Relation(parentColumn = "id", entityColumn = "resultId") val issues: List<ResultIssueEntity>,
)

@Dao
interface ResultDao {
    @Transaction
    @Query("SELECT * FROM suno_result WHERE snapshotId = :snapshotId ORDER BY takeIndex, id")
    fun observeForSnapshot(snapshotId: Long): Flow<List<ResultWithIssues>>

    @Transaction
    @Query("SELECT * FROM suno_result WHERE snapshotId = :snapshotId ORDER BY takeIndex, id")
    suspend fun forSnapshot(snapshotId: Long): List<ResultWithIssues>

    @Transaction
    @Query("SELECT r.* FROM suno_result r JOIN snapshot s ON s.id = r.snapshotId WHERE s.songId = :songId ORDER BY r.snapshotId, r.takeIndex")
    fun observeForSong(songId: Long): Flow<List<ResultWithIssues>>

    @Transaction
    @Query("SELECT * FROM suno_result ORDER BY snapshotId, takeIndex")
    fun observeAll(): Flow<List<ResultWithIssues>>

    @Query("SELECT DISTINCT s.songId FROM suno_result r JOIN snapshot s ON s.id = r.snapshotId")
    fun observeSongIdsWithResults(): Flow<List<Long>>

    @Query("SELECT COALESCE(MAX(takeIndex), 0) FROM suno_result WHERE snapshotId = :snapshotId")
    suspend fun maxTakeIndex(snapshotId: Long): Int

    @Insert
    suspend fun insert(result: SunoResultEntity): Long

    @Update
    suspend fun update(result: SunoResultEntity)

    @Query("SELECT * FROM suno_result WHERE id = :id")
    suspend fun get(id: Long): SunoResultEntity?

    @Query("DELETE FROM suno_result WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM result_issue WHERE resultId = :resultId")
    suspend fun clearIssues(resultId: Long)

    @Insert
    suspend fun insertIssues(issues: List<ResultIssueEntity>)

    @Transaction
    suspend fun replaceIssues(resultId: Long, tags: List<String>) {
        clearIssues(resultId)
        insertIssues(tags.distinct().map { ResultIssueEntity(resultId, it) })
    }

    // Winners

    @Query("SELECT * FROM song_winner WHERE songId = :songId")
    fun observeWinner(songId: Long): Flow<SongWinnerEntity?>

    @Query("SELECT * FROM song_winner WHERE songId = :songId")
    suspend fun winner(songId: Long): SongWinnerEntity?

    @Query("SELECT * FROM song_winner")
    fun observeWinners(): Flow<List<SongWinnerEntity>>

    @Upsert
    suspend fun setWinner(winner: SongWinnerEntity)

    @Query("DELETE FROM song_winner WHERE songId = :songId")
    suspend fun clearWinner(songId: Long)

    // Issue fixes

    @Query("SELECT * FROM issue_fix ORDER BY tag COLLATE NOCASE")
    fun observeFixes(): Flow<List<IssueFixEntity>>

    @Query("SELECT * FROM issue_fix")
    suspend fun fixes(): List<IssueFixEntity>

    @Upsert
    suspend fun upsertFix(fix: IssueFixEntity)

    @Query("DELETE FROM issue_fix WHERE tag = :tag")
    suspend fun deleteFix(tag: String)
}

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipe ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipe ORDER BY createdAt, id")
    suspend fun getAll(): List<RecipeEntity>

    @Query("SELECT * FROM recipe WHERE id = :id")
    suspend fun get(id: Long): RecipeEntity?

    @Query("SELECT * FROM recipe WHERE snapshotId = :snapshotId LIMIT 1")
    fun observeForSnapshot(snapshotId: Long): Flow<RecipeEntity?>

    @Query("SELECT COUNT(*) FROM recipe")
    suspend fun count(): Int

    @Insert
    suspend fun insert(recipe: RecipeEntity): Long

    @Query("DELETE FROM recipe WHERE id = :id")
    suspend fun delete(id: Long)

    /** Best take rating per snapshot, for recipes' live ratings. */
    @Query("SELECT snapshotId, MAX(rating) AS best FROM suno_result WHERE rating IS NOT NULL GROUP BY snapshotId")
    fun observeBestRatings(): Flow<Map<@MapColumn("snapshotId") Long, @MapColumn("best") Int>>
}
