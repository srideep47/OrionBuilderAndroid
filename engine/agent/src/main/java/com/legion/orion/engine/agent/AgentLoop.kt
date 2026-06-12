package com.legion.orion.engine.agent

import com.legion.orion.engine.providers.ChatMessage
import com.legion.orion.engine.providers.ChatProvider
import com.legion.orion.engine.providers.ChatRequest
import com.legion.orion.engine.providers.StreamEvent
import com.legion.orion.engine.providers.ToolCall
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Events surfaced to the UI as the agent runs. */
sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ThinkingDelta(val text: String) : AgentEvent
    data class AssistantMessage(val text: String, val toolCalls: List<ToolCall>) : AgentEvent
    data class ToolStarted(val id: String, val name: String, val argsJson: String) : AgentEvent
    data class ToolFinished(val id: String, val name: String, val result: ToolResult, val snapshot: SnapshotRef?) : AgentEvent
    data class Compacted(val removedMessages: Int) : AgentEvent
    object TurnComplete : AgentEvent
    data class Failed(val message: String) : AgentEvent
}

data class AgentConfig(
    val model: String,
    val maxIterations: Int = 12,
    val contextWindowTokens: Int = 32_768,
    val compactionThreshold: Double = 0.75,
)

/**
 * Drives a multi-turn tool-using conversation. Works with both NATIVE (cloud) and PROMPT_FORMAT
 * (local MNN) providers — the provider abstraction hides the difference. Snapshots before each
 * mutating tool batch, auto-compacts when the context grows past the threshold, and serializes
 * file mutations behind the project mutex.
 */
class AgentLoop(
    private val provider: ChatProvider,
    private val registry: ToolRegistry,
    private val snapshotter: Snapshotter,
    private val config: AgentConfig,
    private val projectDir: File,
    private val systemPrompt: String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = ProjectLocks.forPath(projectDir.canonicalPath)

    fun run(chatId: String, history: List<ChatMessage>): Flow<AgentEvent> = flow {
        val messages = history.toMutableList()
        val ctx = ToolContext(projectDir)

        repeat(config.maxIterations) { iteration ->
            // Auto-compaction before each model call.
            val removed = maybeCompact(messages)
            if (removed > 0) emit(AgentEvent.Compacted(removed))

            val request = ChatRequest(
                model = config.model,
                messages = messages,
                system = systemPrompt,
                tools = registry.schemas(),
            )

            val text = StringBuilder()
            val calls = LinkedHashMap<String, MutableToolCall>()
            var errorMsg: String? = null

            provider.stream(request).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> { text.append(event.text); emit(AgentEvent.TextDelta(event.text)) }
                    is StreamEvent.ThinkingDelta -> emit(AgentEvent.ThinkingDelta(event.text))
                    is StreamEvent.ToolCallStart -> calls[event.id] = MutableToolCall(event.id, event.name)
                    is StreamEvent.ToolCallArgsDelta -> calls[event.id]?.args?.append(event.jsonChunk)
                    is StreamEvent.ToolCallEnd -> {}
                    is StreamEvent.Error -> errorMsg = event.message
                    is StreamEvent.Usage -> {}
                }
            }

            if (errorMsg != null) { emit(AgentEvent.Failed(errorMsg!!)); return@flow }

            val toolCalls = calls.values.map { ToolCall(it.id, it.name, it.args.toString().ifBlank { "{}" }) }

            // Record the assistant turn (with any tool calls) into the running history.
            messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, text.toString(), toolCalls = toolCalls))
            emit(AgentEvent.AssistantMessage(text.toString(), toolCalls))

            if (toolCalls.isEmpty()) {
                emit(AgentEvent.TurnComplete)
                return@flow
            }

            // Snapshot once per batch, before any mutating tool runs.
            var snapshot: SnapshotRef? = null
            if (toolCalls.any { registry.get(it.name)?.mutating == true }) {
                snapshot = runCatching { snapshotter.snapshot(chatId, messageIdFor(iteration)) }.getOrNull()
            }

            for (call in toolCalls) {
                emit(AgentEvent.ToolStarted(call.id, call.name, call.argumentsJson))
                val result = executeTool(call, ctx)
                emit(AgentEvent.ToolFinished(call.id, call.name, result, snapshot))
                messages.add(
                    ChatMessage(
                        role = ChatMessage.Role.TOOL,
                        content = result.content,
                        toolCallId = call.id,
                        toolName = call.name,
                    ),
                )
            }
            // loop continues: model sees tool results and responds
        }
        emit(AgentEvent.Failed("Reached max iterations (${config.maxIterations}) without completing"))
    }

    private suspend fun executeTool(call: ToolCall, ctx: ToolContext): ToolResult {
        val tool = registry.get(call.name)
            ?: return ToolResult("Unknown tool '${call.name}'", isError = true)
        val args = runCatching { json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull()
            ?: return ToolResult("Invalid JSON arguments for '${call.name}': ${call.argumentsJson}", isError = true)
        return try {
            if (tool.mutating) lock.withLock { tool.execute(args, ctx) }
            else tool.execute(args, ctx)
        } catch (t: Throwable) {
            ToolResult("Tool '${call.name}' failed: ${t.message}", isError = true)
        }
    }

    /** Rough char/4 token estimate; compacts the middle of the history when over threshold. */
    private fun maybeCompact(messages: MutableList<ChatMessage>): Int {
        val budget = (config.contextWindowTokens * config.compactionThreshold).toInt()
        val estimate = messages.sumOf { it.content.length / 4 + 8 }
        if (estimate <= budget || messages.size <= 4) return 0
        // Keep the first user message and the last 3 exchanges; summarize the rest into a note.
        val keepHead = 1
        val keepTail = 6.coerceAtMost(messages.size - keepHead)
        val middle = messages.subList(keepHead, messages.size - keepTail)
        if (middle.isEmpty()) return 0
        val removedCount = middle.size
        val summary = "[Earlier conversation compacted: $removedCount messages summarized. " +
            "Key files touched and decisions remain in effect.]"
        middle.clear()
        messages.add(keepHead, ChatMessage(ChatMessage.Role.USER, summary))
        return removedCount
    }

    private fun messageIdFor(iteration: Int) = "turn_$iteration"

    private class MutableToolCall(val id: String, val name: String) {
        val args = StringBuilder()
    }
}
