package com.legion.orion.core.database

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val path: String,
    val stackType: String,
    val templateId: String?,
    val gitInitialized: Boolean = false,
    val createdAt: Long,
    val lastOpenedAt: Long?,
)

@Entity(tableName = "chats", indices = [Index("projectId")])
data class ChatEntity(
    @PrimaryKey val id: String,
    val projectId: String?,
    val title: String,
    /** agent | ask */
    val mode: String,
    val summary: String?,
    val createdAt: Long,
)

@Entity(tableName = "messages", indices = [Index("chatId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    /** user | assistant | system | tool */
    val role: String,
    /** streaming | complete | error | cancelled */
    val status: String,
    val createdAt: Long,
)

@Entity(tableName = "message_parts", indices = [Index("messageId")])
data class MessagePartEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val seq: Int,
    /** text | thinking | tool_call | tool_result | image */
    val type: String,
    val json: String,
)

@Entity(tableName = "turn_snapshots", indices = [Index("chatId"), Index("messageId")])
data class TurnSnapshotEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val messageId: String,
    val gitRef: String,
    val commitHash: String,
    val createdAt: Long,
)

@Entity(tableName = "todos", indices = [Index("chatId")])
data class TodoEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val content: String,
    /** pending | in_progress | completed */
    val status: String,
    val seq: Int,
)

@Entity(tableName = "local_models")
data class LocalModelEntity(
    @PrimaryKey val id: String,
    /** hf | modelscope | imported */
    val source: String,
    val repoId: String?,
    val localPath: String,
    val format: String = "mnn",
    val quant: String?,
    val sizeBytes: Long,
    /** comma-separated: chat,vision,thinking,omni */
    val capabilities: String,
    val lastUsedBackend: String?,
)

@Entity(tableName = "downloads", indices = [Index("modelId")])
data class DownloadEntity(
    @PrimaryKey val id: String,
    val modelId: String,
    /** queued | running | paused | done | failed */
    val state: String,
    val totalBytes: Long,
    val doneBytes: Long,
    val partsJson: String?,
    val error: String?,
)

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    /** anthropic | openai | google | openai_compat | mnn_local */
    val type: String,
    val baseUrl: String?,
    val label: String,
)

@Entity(tableName = "provider_models", indices = [Index("providerId")])
data class ProviderModelEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val modelId: String,
    val contextWindow: Int,
    val supportsTools: Boolean,
    val supportsVision: Boolean,
)

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val publicKey: String,
    val name: String,
    val platform: String?,
    val lastSeen: Long?,
    val isFriend: Boolean = false,
    /** none | pending_out | pending_in | approved */
    val approvalState: String = "none",
    val capabilitiesJson: String?,
    val lastLoadJson: String?,
)

@Entity(tableName = "media_jobs")
data class MediaJobEntity(
    @PrimaryKey val id: String,
    /** image | video | audio */
    val type: String,
    val prompt: String,
    val paramsJson: String?,
    /** queued | assigned | running | done | failed */
    val state: String,
    val assignedPeer: String?,
    val resultPath: String?,
    val progress: Int = 0,
    val createdAt: Long,
)

@Entity(tableName = "build_jobs", indices = [Index("projectId")])
data class BuildJobEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    /** apk | win | linux | mac */
    val target: String,
    /** queued | running | done | failed */
    val state: String,
    val peer: String?,
    val logTail: String?,
    val artifactPath: String?,
)

@Entity(tableName = "publish_jobs")
data class PublishJobEntity(
    @PrimaryKey val id: String,
    /** youtube | instagram */
    val platform: String,
    val mediaPath: String,
    val metadataJson: String?,
    val scheduledAt: Long?,
    /** scheduled | uploading | done | failed */
    val state: String,
    /** resumable-upload checkpoint (session URI + offset) */
    val uploadSessionJson: String?,
)

@Fts4
@Entity(tableName = "chat_fts")
data class ChatFtsEntity(
    @PrimaryKey(autoGenerate = true) val rowid: Long = 0,
    val messageId: String,
    val content: String,
)
