package app.sunosongwriter.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folder")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "song",
    foreignKeys = [ForeignKey(FolderEntity::class, ["id"], ["folderId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("folderId")],
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long?,
    val title: String,
    val artist: String,
    val concept: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "song_tag",
    primaryKeys = ["songId", "tag"],
    foreignKeys = [ForeignKey(SongEntity::class, ["id"], ["songId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tag")],
)
data class SongTagEntity(val songId: Long, val tag: String)

@Entity(
    tableName = "variant",
    foreignKeys = [ForeignKey(SongEntity::class, ["id"], ["songId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("songId")],
)
data class VariantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val name: String,
    val createdAt: Long,
)

/** Immutable: rows are only ever inserted (spec 4). */
@Entity(
    tableName = "version",
    foreignKeys = [ForeignKey(VariantEntity::class, ["id"], ["variantId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("variantId")],
)
data class VersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val variantId: Long,
    val number: Int,
    /** May point into another variant (a variant created from an existing version). Not a foreign key. */
    val parentVersionId: Long?,
    /** JSON of [app.sunosongwriter.domain.model.StylePrompt]. */
    val styleJson: String,
    val lyrics: String,
    /** JSON of [app.sunosongwriter.domain.model.SunoSettings]. */
    val settingsJson: String,
    /** [app.sunosongwriter.domain.model.AuthorType] name. */
    val authorType: String,
    val authorLabel: String,
    val participantId: Long?,
    val step: String?,
    val changeNote: String,
    val createdAt: Long,
)

/**
 * Full-text index for library search (spec 5.3). `rowid` is the song id. Kept in sync by
 * [app.sunosongwriter.data.repo.SongRepository] whenever a song, its tags or its latest versions change.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "song_search")
data class SongSearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val songId: Long,
    val title: String,
    val artist: String,
    val tags: String,
    val concept: String,
    val lyrics: String,
)

/**
 * Exactly what was copied into Suno, frozen when the user marked it sent (spec 5.11). Rows are only
 * ever inserted; later edits never change a snapshot.
 */
@Entity(
    tableName = "snapshot",
    foreignKeys = [ForeignKey(SongEntity::class, ["id"], ["songId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("songId"), Index("versionId")],
)
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    /** The version it was taken from. Not a foreign key: the snapshot outlives a deleted variant. */
    val versionId: Long,
    /** "Consensus v5", kept for display after variants are renamed or deleted. */
    val versionLabel: String,
    val styleText: String,
    val lyricsText: String,
    /** JSON of [app.sunosongwriter.domain.model.SunoSettings]. */
    val settingsJson: String,
    val sentAt: Long,
)
