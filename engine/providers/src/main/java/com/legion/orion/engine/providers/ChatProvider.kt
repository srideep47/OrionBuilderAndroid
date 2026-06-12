package com.legion.orion.engine.providers

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** How a provider expects tool calls to be expressed. */
enum class ToolCallingMode { NATIVE, PROMPT_FORMAT }

/** A tool the model may call. [parameters] is a JSON Schema object. */
data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** A tool call requested by the assistant. */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/** A single conversation message handed to a provider. */
data class ChatMessage(
    val role: Role,
    val content: String,
    /** Present on ASSISTANT messages that requested tool calls. */
    val toolCalls: List<ToolCall> = emptyList(),
    /** Present on TOOL messages: which call this is the result of. */
    val toolCallId: String? = null,
    /** Present on TOOL messages: the tool name (needed by some providers). */
    val toolName: String? = null,
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

/** Sampling + routing parameters for one streamed completion. */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val system: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    /** Tools offered to the model this turn (empty = plain chat). */
    val tools: List<ToolSchema> = emptyList(),
    /** Carries the partial assistant text when resuming an interrupted stream. */
    val assistantPrefix: String? = null,
)

/**
 * Incremental events from a streamed completion. Text and thinking are kept separate so the UI
 * can render collapsible reasoning; tool events drive the agent loop.
 */
sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ThinkingDelta(val text: String) : StreamEvent
    data class ToolCallStart(val id: String, val name: String) : StreamEvent
    data class ToolCallArgsDelta(val id: String, val jsonChunk: String) : StreamEvent
    data class ToolCallEnd(val id: String) : StreamEvent
    data class Usage(val promptTokens: Int, val completionTokens: Int) : StreamEvent
    data class Error(val message: String, val retryable: Boolean) : StreamEvent
}

/**
 * A model backend (cloud API or local engine). Implementations must emit on a background
 * dispatcher and respect Flow cancellation to stop generation.
 */
interface ChatProvider {
    val toolCallingMode: ToolCallingMode
    fun stream(request: ChatRequest): Flow<StreamEvent>
}
