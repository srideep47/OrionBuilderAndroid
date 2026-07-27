package com.legion.orion.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        ProjectEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        MessagePartEntity::class,
        TurnSnapshotEntity::class,
        TodoEntity::class,
        LocalModelEntity::class,
        DownloadEntity::class,
        ProviderEntity::class,
        ProviderModelEntity::class,
        PeerEntity::class,
        MediaJobEntity::class,
        BuildJobEntity::class,
        PublishJobEntity::class,
        ChatFtsEntity::class,
        ClaudeCodeSessionEntity::class,
        CodexSessionEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class OrionDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun todoDao(): TodoDao
    abstract fun localModelDao(): LocalModelDao
    abstract fun downloadDao(): DownloadDao
    abstract fun providerDao(): ProviderDao
    abstract fun peerDao(): PeerDao
    abstract fun jobsDao(): JobsDao
    abstract fun chatFtsDao(): ChatFtsDao
    abstract fun claudeCodeSessionDao(): ClaudeCodeSessionDao
    abstract fun codexSessionDao(): CodexSessionDao
}

/**
 * Adds the `codex_sessions` table (see [CodexSessionEntity]).
 *
 * This is written as a real migration rather than leaning on the
 * [RoomDatabase.Builder.fallbackToDestructiveMigration] below, which is still in place for other
 * schema churn. That fallback is fine for a pre-release schema change that only affects developer
 * test data, but by now real devices carry real state — chat history, projects, provider
 * configuration, and (most expensively) the Claude Code session ids that make `--resume` work. This
 * change only ADDS a table, so destroying all of that to get it would be gratuitous.
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `codex_sessions` (" +
                "`projectId` TEXT NOT NULL, " +
                "`threadId` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`projectId`))",
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providesDatabase(@ApplicationContext context: Context): OrionDatabase =
        Room.databaseBuilder(context, OrionDatabase::class.java, "orion.db")
            .addMigrations(MIGRATION_2_3)
            .fallbackToDestructiveMigrationOnDowngrade()
            .fallbackToDestructiveMigration() // pre-release: no migration path yet for other schema bumps
            .build()

    @Provides fun projectDao(db: OrionDatabase) = db.projectDao()
    @Provides fun chatDao(db: OrionDatabase) = db.chatDao()
    @Provides fun messageDao(db: OrionDatabase) = db.messageDao()
    @Provides fun snapshotDao(db: OrionDatabase) = db.snapshotDao()
    @Provides fun todoDao(db: OrionDatabase) = db.todoDao()
    @Provides fun localModelDao(db: OrionDatabase) = db.localModelDao()
    @Provides fun downloadDao(db: OrionDatabase) = db.downloadDao()
    @Provides fun providerDao(db: OrionDatabase) = db.providerDao()
    @Provides fun peerDao(db: OrionDatabase) = db.peerDao()
    @Provides fun jobsDao(db: OrionDatabase) = db.jobsDao()
    @Provides fun chatFtsDao(db: OrionDatabase) = db.chatFtsDao()
    @Provides fun claudeCodeSessionDao(db: OrionDatabase) = db.claudeCodeSessionDao()
    @Provides fun codexSessionDao(db: OrionDatabase) = db.codexSessionDao()
}
