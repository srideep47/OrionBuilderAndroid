package com.legion.orion.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    ],
    version = 2,
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
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providesDatabase(@ApplicationContext context: Context): OrionDatabase =
        Room.databaseBuilder(context, OrionDatabase::class.java, "orion.db")
            .fallbackToDestructiveMigrationOnDowngrade()
            .fallbackToDestructiveMigration() // pre-release: no migration path yet for schema bumps
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
}
