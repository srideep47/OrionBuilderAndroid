package com.legion.orion.data.chat

import com.legion.orion.core.database.ChatDao
import com.legion.orion.core.database.ChatEntity
import com.legion.orion.core.database.ChatFtsDao
import com.legion.orion.core.database.ChatFtsEntity
import com.legion.orion.core.database.MessageDao
import com.legion.orion.core.database.MessageEntity
import com.legion.orion.core.database.MessagePartEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Part types persisted in message_parts.type. */
object PartType {
    const val TEXT = "text"
    const val THINKING = "thinking"
    const val TOOL_CALL = "tool_call"
}

object MsgStatus {
    const val STREAMING = "streaming"
    const val COMPLETE = "complete"
    const val ERROR = "error"
    const val CANCELLED = "cancelled"
}

object MsgRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

@Serializable
private data class PartPayload(val text: String)

/** A tool-call card's data — name, raw args, status (running|done|error), result text, and the
 * shadow-ref commit to roll back to. Mirrors the desktop tool-call presentation. [diff], if present,
 * is a pre-formatted unified diff (from Claude Code's own `structuredPatch` on Edit tool results) to
 * render instead of the raw before/after text. */
@Serializable
data class ToolView(
    val name: String,
    val args: String = "",
    val status: String = "running",
    val result: String? = null,
    val snapshotRef: String? = null,
    val snapshotCommit: String? = null,
    val diff: String? = null,
)

/** A message with its ordered parts, ready for rendering. */
data class UiPart(val type: String, val text: String, val tool: ToolView? = null)
data class UiMessage(
    val id: String,
    val role: String,
    val status: String,
    val parts: List<UiPart>,
    val createdAt: Long,
)

/**
 * Persists chats, messages and message parts to Room and mirrors text into the FTS index.
 * Completed turns are durable across process restarts; the in-flight assistant turn is held in
 * the ViewModel and only flushed here on completion / error / cancel.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val ftsDao: ChatFtsDao,
) {
    private val json = Json { encodeDefaults = true }
    private fun clock(): Long = System.currentTimeMillis()

    fun observeChats(): Flow<List<ChatEntity>> = chatDao.observeAll()

    suspend fun getChat(id: String): ChatEntity? = chatDao.get(id)

    suspend fun createChat(title: String, mode: String = "ask"): ChatEntity {
        val chat = ChatEntity(
            id = UUID.randomUUID().toString(),
            projectId = null,
            title = title,
            mode = mode,
            summary = null,
            createdAt = clock(),
        )
        chatDao.upsert(chat)
        return chat
    }

    suspend fun renameChat(chat: ChatEntity, title: String) = chatDao.upsert(chat.copy(title = title))

    suspend fun renameChat(chatId: String, title: String) {
        val chat = chatDao.get(chatId) ?: return
        chatDao.upsert(chat.copy(title = title))
    }

    suspend fun updateSummary(chatId: String, summary: String?) {
        val chat = chatDao.get(chatId) ?: return
        chatDao.upsert(chat.copy(summary = summary))
    }

    suspend fun bindChatProject(chatId: String, projectId: String?, mode: String) {
        val chat = chatDao.get(chatId) ?: return
        chatDao.upsert(chat.copy(projectId = projectId, mode = mode))
    }

    suspend fun deleteChat(id: String) {
        messageDao.deleteForChat(id)
        chatDao.delete(id)
    }

    /** Observes stored messages (with parts) for a chat, oldest first. */
    fun observeMessages(chatId: String): Flow<List<UiMessage>> =
        messageDao.observeForChat(chatId).map { rows ->
            rows.map { msg ->
                val parts = messageDao.partsFor(msg.id).map { p ->
                    if (p.type == PartType.TOOL_CALL) {
                        UiPart(p.type, "", runCatching { json.decodeFromString<ToolView>(p.json) }.getOrNull())
                    } else {
                        UiPart(p.type, decode(p.json))
                    }
                }
                UiMessage(msg.id, msg.role, msg.status, parts, msg.createdAt)
            }
        }

    /** Persists a completed user message (single text part) and indexes it. */
    suspend fun addUserMessage(chatId: String, text: String): String {
        val id = UUID.randomUUID().toString()
        messageDao.upsert(MessageEntity(id, chatId, MsgRole.USER, MsgStatus.COMPLETE, clock()))
        messageDao.upsertPart(MessagePartEntity(UUID.randomUUID().toString(), id, 0, PartType.TEXT, encode(text)))
        ftsDao.insert(ChatFtsEntity(messageId = id, content = text))
        return id
    }

    /** Creates an assistant message in streaming state; parts are written on completion. */
    suspend fun beginAssistantMessage(chatId: String): String {
        val id = UUID.randomUUID().toString()
        messageDao.upsert(MessageEntity(id, chatId, MsgRole.ASSISTANT, MsgStatus.STREAMING, clock()))
        return id
    }

    /**
     * Finalizes an assistant message: writes thinking (if any) then text parts, sets status, and
     * indexes the visible text. Pass [status] = COMPLETE / ERROR / CANCELLED.
     */
    suspend fun finishAssistantMessage(
        chatId: String,
        messageId: String,
        text: String,
        thinking: String,
        status: String,
    ) {
        var seq = 0
        if (thinking.isNotEmpty()) {
            messageDao.upsertPart(
                MessagePartEntity(UUID.randomUUID().toString(), messageId, seq++, PartType.THINKING, encode(thinking)),
            )
        }
        if (text.isNotEmpty()) {
            messageDao.upsertPart(
                MessagePartEntity(UUID.randomUUID().toString(), messageId, seq, PartType.TEXT, encode(text)),
            )
        }
        messageDao.update(MessageEntity(messageId, chatId, MsgRole.ASSISTANT, status, clock()))
        if (text.isNotEmpty()) ftsDao.insert(ChatFtsEntity(messageId = messageId, content = text))
    }

    /**
     * Finalizes an assistant (agent) turn from an ordered list of parts — text, thinking, and
     * tool-call cards — preserving order so the transcript renders exactly like it streamed.
     */
    suspend fun finishAssistantParts(
        chatId: String,
        messageId: String,
        parts: List<UiPart>,
        status: String,
    ) {
        parts.forEachIndexed { seq, part ->
            val payload = if (part.type == PartType.TOOL_CALL && part.tool != null) {
                json.encodeToString(ToolView.serializer(), part.tool)
            } else {
                encode(part.text)
            }
            messageDao.upsertPart(MessagePartEntity(UUID.randomUUID().toString(), messageId, seq, part.type, payload))
        }
        messageDao.update(MessageEntity(messageId, chatId, MsgRole.ASSISTANT, status, clock()))
        val visible = parts.filter { it.type == PartType.TEXT }.joinToString("\n") { it.text }
        if (visible.isNotEmpty()) ftsDao.insert(ChatFtsEntity(messageId = messageId, content = visible))
    }

    /** Returns message IDs whose text matches an FTS query. */
    suspend fun search(query: String): List<String> = ftsDao.search(query)

    private fun encode(text: String): String = json.encodeToString(PartPayload(text))
    private fun decode(stored: String): String =
        runCatching { json.decodeFromString<PartPayload>(stored).text }.getOrDefault(stored)
}
