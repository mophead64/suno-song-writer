package app.sunosongwriter.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BackendEntity::class,
        ModelInfoEntity::class,
        ParticipantEntity::class,
        RequestCountEntity::class,
        FolderEntity::class,
        SongEntity::class,
        SongTagEntity::class,
        VariantEntity::class,
        VersionEntity::class,
        SongSearchEntity::class,
        PromptTemplateEntity::class,
        TemplateDefaultEntity::class,
        SessionEntity::class,
        SessionMessageEntity::class,
        SuggestionEntity::class,
        SnapshotEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        // 1 → 2: library tables (iteration 2). Additive only.
        AutoMigration(from = 1, to = 2),
        // 2 → 3: relay backends, templates, sessions and suggestions (iteration 3). Additive only.
        AutoMigration(from = 2, to = 3),
        // 3 → 4: session_message.warning (iteration 3). Additive only.
        AutoMigration(from = 3, to = 4),
        // 4 → 5: Writers' Room config/state, step targets, proposals, cost (iteration 4). Additive only.
        AutoMigration(from = 4, to = 5),
        // 5 → 6: snapshots of what was sent to Suno (iteration 5). Additive only.
        AutoMigration(from = 5, to = 6),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun backendDao(): BackendDao
    abstract fun modelInfoDao(): ModelInfoDao
    abstract fun participantDao(): ParticipantDao
    abstract fun requestCountDao(): RequestCountDao
    abstract fun folderDao(): FolderDao
    abstract fun songDao(): SongDao
    abstract fun versionDao(): VersionDao
    abstract fun templateDao(): TemplateDao
    abstract fun sessionDao(): SessionDao
    abstract fun snapshotDao(): SnapshotDao

    companion object {
        const val NAME = "songwriter.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}
