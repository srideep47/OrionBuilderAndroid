package com.legion.orion.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT * FROM projects ORDER BY lastOpenedAt DESC")
    suspend fun getAllOnce(): List<ProjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun get(id: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: ChatEntity)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun observeForChat(chatId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM message_parts WHERE messageId = :messageId ORDER BY seq ASC")
    suspend fun partsFor(messageId: String): List<MessagePartEntity>

    @Query("SELECT * FROM message_parts WHERE messageId IN (:messageIds) ORDER BY seq ASC")
    fun observeParts(messageIds: List<String>): Flow<List<MessagePartEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPart(part: MessagePartEntity)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteForChat(chatId: String)
}

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM turn_snapshots WHERE chatId = :chatId ORDER BY createdAt DESC")
    suspend fun forChat(chatId: String): List<TurnSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: TurnSnapshotEntity)
}

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos WHERE chatId = :chatId ORDER BY seq ASC")
    fun observeForChat(chatId: String): Flow<List<TodoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(todos: List<TodoEntity>)
}

@Dao
interface LocalModelDao {
    @Query("SELECT * FROM local_models")
    fun observeAll(): Flow<List<LocalModelEntity>>

    @Query("SELECT * FROM local_models")
    suspend fun all(): List<LocalModelEntity>

    @Query("SELECT * FROM local_models WHERE id = :id")
    suspend fun get(id: String): LocalModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: LocalModelEntity)

    @Query("DELETE FROM local_models WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM provider_models WHERE providerId = :providerId")
    suspend fun modelsFor(providerId: String): List<ProviderModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModel(model: ProviderModelEntity)
}

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PeerEntity)
}

@Dao
interface JobsDao {
    @Query("SELECT * FROM media_jobs ORDER BY createdAt DESC")
    fun observeMediaJobs(): Flow<List<MediaJobEntity>>

    @Query("SELECT * FROM media_jobs WHERE id = :id")
    suspend fun mediaJob(id: String): MediaJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaJob(job: MediaJobEntity)

    @Query("DELETE FROM media_jobs WHERE id = :id")
    suspend fun deleteMediaJob(id: String)

    @Query("SELECT * FROM publish_jobs WHERE id = :id")
    suspend fun publishJob(id: String): PublishJobEntity?

    @Query("SELECT * FROM build_jobs")
    fun observeBuildJobs(): Flow<List<BuildJobEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBuildJob(job: BuildJobEntity)

    @Query("SELECT * FROM publish_jobs")
    fun observePublishJobs(): Flow<List<PublishJobEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPublishJob(job: PublishJobEntity)
}

@Dao
interface ChatFtsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: ChatFtsEntity)

    @Query("SELECT messageId FROM chat_fts WHERE chat_fts MATCH :query")
    suspend fun search(query: String): List<String>
}

@Dao
interface ClaudeCodeSessionDao {
    @Query("SELECT * FROM claude_code_sessions WHERE projectId = :projectId")
    suspend fun get(projectId: String): ClaudeCodeSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ClaudeCodeSessionEntity)

    /** A Claude session belongs to the signed-in account that created it; never resume it after an account change. */
    @Query("DELETE FROM claude_code_sessions")
    suspend fun clearAll()
}

@Dao
interface CodexSessionDao {
    @Query("SELECT * FROM codex_sessions WHERE projectId = :projectId")
    suspend fun get(projectId: String): CodexSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: CodexSessionEntity)

    /** Drops one project's thread id so its next turn starts a brand-new Codex conversation. Needed
     * because a stored id can go stale on its own (a thread created with `--ephemeral` is never
     * resumable, and `codex exec resume` on a missing id silently starts a fresh thread instead of
     * failing) — so the id must be clearable without wiping every other project's. */
    @Query("DELETE FROM codex_sessions WHERE projectId = :projectId")
    suspend fun clear(projectId: String)

    /** A Codex thread belongs to the signed-in account that created it; never resume it after an account change. */
    @Query("DELETE FROM codex_sessions")
    suspend fun clearAll()
}

@Dao
interface GrokSessionDao {
    @Query("SELECT * FROM grok_sessions WHERE projectId = :projectId")
    suspend fun get(projectId: String): GrokSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: GrokSessionEntity)

    @Query("DELETE FROM grok_sessions WHERE projectId = :projectId")
    suspend fun clear(projectId: String)

    /** A Grok session belongs to the signed-in account that created it; never resume it after an account change. */
    @Query("DELETE FROM grok_sessions")
    suspend fun clearAll()
}
