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

/** One Suno take generated from a snapshot (spec 5.12): a link, a rating and notes. No audio is stored. */
@Entity(
    tableName = "suno_result",
    foreignKeys = [ForeignKey(SnapshotEntity::class, ["id"], ["snapshotId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("snapshotId")],
)
data class SunoResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val snapshotId: Long,
    /** 1, 2, 3… within the snapshot. */
    val takeIndex: Int,
    val link: String?,
    /** 1–5 stars, or null when not rated. */
    val rating: Int?,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** An issue tag on a take: a built-in key from [app.sunosongwriter.domain.results.BuiltInIssues] or a custom tag's name. */
@Entity(
    tableName = "result_issue",
    primaryKeys = ["resultId", "tag"],
    foreignKeys = [ForeignKey(SunoResultEntity::class, ["id"], ["resultId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tag")],
)
data class ResultIssueEntity(val resultId: Long, val tag: String)

/** Which variant won for a song (spec 5.12). Variants are compared by name, so win rates work across songs (5.15). */
@Entity(
    tableName = "song_winner",
    foreignKeys = [ForeignKey(SongEntity::class, ["id"], ["songId"], onDelete = ForeignKey.CASCADE)],
)
data class SongWinnerEntity(
    @PrimaryKey val songId: Long,
    val variantName: String,
    /** The snapshot it was marked from. Not a foreign key. */
    val snapshotId: Long?,
    val markedAt: Long,
)

/**
 * The tag → fix table (spec 5.13). Built-in fixes live in code; a row here either overrides a built-in's
 * fix text ([builtIn] true) or is a custom tag the user added.
 */
@Entity(tableName = "issue_fix")
data class IssueFixEntity(
    @PrimaryKey val tag: String,
    val fixText: String,
    val builtIn: Boolean,
    val updatedAt: Long,
)

/**
 * A snapshot saved as a proven recipe (spec 5.14). Never edited. It keeps its own copy of the text and
 * song details, so it outlives the song; adapting it for a new Suno model saves a new recipe linked by
 * [parentRecipeId].
 */
@Entity(tableName = "recipe", indices = [Index("snapshotId"), Index("parentRecipeId")])
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Not a foreign key: the recipe outlives the song. Null for an adapted recipe. */
    val snapshotId: Long?,
    val songId: Long?,
    val parentRecipeId: Long?,
    val songTitle: String,
    val artist: String,
    /** JSON list of genre tags, copied from the song. */
    val tagsJson: String,
    val styleText: String,
    val lyricsText: String,
    /** JSON of [app.sunosongwriter.domain.model.SunoSettings]. */
    val settingsJson: String,
    /** The best take's rating when it was saved; the live rating is used while the snapshot exists. */
    val ratingAtSave: Int?,
    val createdAt: Long,
)
