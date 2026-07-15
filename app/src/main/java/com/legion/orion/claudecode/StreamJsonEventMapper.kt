package com.legion.orion.claudecode

import com.legion.orion.data.chat.PartType
import com.legion.orion.data.chat.ToolView
import com.legion.orion.data.chat.UiPart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Translates one turn's stream of raw Claude Code `stream-json` events (see [ClaudeCodeProcess]) into
 * the same `UiPart`/`ToolView` shapes the existing BUILD-mode agent loop already produces, so the
 * existing `ToolCard`/`ThinkingBlock`/markdown-text Compose UI renders Claude Code turns for free.
 *
 * The event shape (captured empirically on-device, not from public docs — Anthropic doesn't publish
 * this envelope) is the Anthropic Messages API's raw SSE event structure wrapped in a
 * `{"type":"stream_event","event":{...}}` envelope for text/thinking/tool-call streaming, plus two
 * Claude-Code-CLI-specific envelope types layered on top: a top-level
 * `{"type":"user","message":{"content":[{"type":"tool_result",...}]}}` event delivering a tool's
 * actual result once Claude Code has executed it (`content_block_stop` only means the model finished
 * *specifying* the call, not that it finished *running* — Claude Code executes tools itself, unlike
 * the other `ChatProvider`s where Orion's own `AgentLoop` executes them), and a terminal
 * `{"type":"result",...}` event marking the whole turn complete.
 *
 * `content_block` indices are scoped per assistant *message*, not per turn — a single turn can span
 * several tool-calling round trips, each starting a fresh `message_start` that resets indices to 0.
 * Per-block bookkeeping is cleared on every `message_start`; the tool-id → parts-index mapping is not
 * (tool ids are globally unique within a turn and the later top-level tool_result event only carries
 * the id, not a block index).
 */
class StreamJsonEventMapper {
    private val parts = mutableListOf<UiPart>()

    private val partIndexByBlockIndex = mutableMapOf<Int, Int>()
    private val argsBuilderByBlockIndex = mutableMapOf<Int, StringBuilder>()
    private val toolIndexById = mutableMapOf<String, Int>()

    val currentParts: List<UiPart> get() = parts.toList()

    sealed interface Outcome {
        data object InProgress : Outcome
        data class TurnComplete(val resultText: String?, val isError: Boolean, val usage: TurnUsage? = null) : Outcome
        data class ProcessDied(val message: String) : Outcome
    }

    /** Cost/token accounting from the terminal `result` event — Claude Code's own billing numbers,
     * not an estimate. `costUsd` is cumulative cost for the whole `--resume`d session as Claude Code
     * itself tracks it (`total_cost_usd`), not just this one turn. */
    data class TurnUsage(
        val costUsd: Double,
        val numTurns: Int,
        val inputTokens: Int,
        val outputTokens: Int,
        val cacheReadTokens: Int,
        val cacheCreationTokens: Int,
    )

    /** Feeds one raw event; mutates [currentParts] in place. Returns the turn's status. */
    fun accept(obj: JsonObject): Outcome {
        when (obj.stringOrNull("type")) {
            "orion_process_died" -> return Outcome.ProcessDied("Claude Code process ended unexpectedly")
            "stream_event" -> (obj["event"] as? JsonObject)?.let(::handleStreamEvent)
            "user" -> handleToolResult(obj)
            "result" -> {
                val isError = (obj["is_error"] as? JsonPrimitive)?.contentOrNull == "true"
                val resultText = obj.stringOrNull("result") ?: (obj["errors"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.stringOrNull("message") }
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString("\n")
                return Outcome.TurnComplete(resultText, isError, parseUsage(obj))
            }
            // "system" (init/status) and "assistant" (a redundant full-message snapshot) and
            // "rate_limit_event" carry no UI-relevant delta beyond what stream_event already gave us.
            else -> {}
        }
        return Outcome.InProgress
    }

    private fun handleStreamEvent(event: JsonObject) {
        when (event.stringOrNull("type")) {
            "message_start" -> {
                partIndexByBlockIndex.clear()
                argsBuilderByBlockIndex.clear()
            }
            "content_block_start" -> {
                val index = event.intOrNull("index") ?: return
                val block = event["content_block"] as? JsonObject ?: return
                when (block.stringOrNull("type")) {
                    "text" -> {
                        parts.add(UiPart(PartType.TEXT, ""))
                        partIndexByBlockIndex[index] = parts.lastIndex
                    }
                    "thinking" -> {
                        parts.add(UiPart(PartType.THINKING, ""))
                        partIndexByBlockIndex[index] = parts.lastIndex
                    }
                    "tool_use" -> {
                        val id = block.stringOrNull("id") ?: return
                        val name = block.stringOrNull("name") ?: "tool"
                        parts.add(UiPart(PartType.TOOL_CALL, "", ToolView(name = name, status = "running")))
                        partIndexByBlockIndex[index] = parts.lastIndex
                        toolIndexById[id] = parts.lastIndex
                        argsBuilderByBlockIndex[index] = StringBuilder()
                    }
                }
            }
            "content_block_delta" -> {
                val index = event.intOrNull("index") ?: return
                val delta = event["delta"] as? JsonObject ?: return
                val partIndex = partIndexByBlockIndex[index] ?: return
                when (delta.stringOrNull("type")) {
                    "text_delta" -> appendText(partIndex, delta.stringOrNull("text").orEmpty())
                    "thinking_delta" -> appendText(partIndex, delta.stringOrNull("thinking").orEmpty())
                    "input_json_delta" -> argsBuilderByBlockIndex[index]?.append(delta.stringOrNull("partial_json").orEmpty())
                    // signature_delta (thinking-block crypto signature): not user-visible, ignored.
                }
            }
            "content_block_stop" -> {
                val index = event.intOrNull("index") ?: return
                val partIndex = partIndexByBlockIndex[index] ?: return
                val args = argsBuilderByBlockIndex[index]?.toString() ?: return
                val existing = parts.getOrNull(partIndex) ?: return
                if (existing.type == PartType.TOOL_CALL && existing.tool != null) {
                    parts[partIndex] = existing.copy(tool = existing.tool.copy(args = args))
                }
            }
        }
    }

    /** Text and thinking parts both accumulate by simple append at a fixed parts-list index. */
    private fun appendText(partIndex: Int, delta: String) {
        if (delta.isEmpty()) return
        val existing = parts.getOrNull(partIndex) ?: return
        parts[partIndex] = existing.copy(text = existing.text + delta)
    }

    private fun handleToolResult(obj: JsonObject) {
        val content = (obj["message"] as? JsonObject)?.get("content") as? JsonArray ?: return
        // structuredPatch lives on a sibling field of "message", not inside the content array itself —
        // present only for Edit-tool results (Write/Bash/Read don't have it).
        val structuredPatch = (obj["tool_use_result"] as? JsonObject)?.get("structuredPatch") as? JsonArray
        for (item in content) {
            val entry = item as? JsonObject ?: continue
            if (entry.stringOrNull("type") != "tool_result") continue
            val toolUseId = entry.stringOrNull("tool_use_id") ?: continue
            val index = toolIndexById[toolUseId] ?: continue
            val existing = parts.getOrNull(index) ?: continue
            val tool = existing.tool ?: continue
            val isError = (entry["is_error"] as? JsonPrimitive)?.contentOrNull == "true"
            val resultText = when (val c = entry["content"]) {
                is JsonPrimitive -> c.contentOrNull
                is JsonArray -> c.joinToString("\n") { (it as? JsonObject)?.stringOrNull("text").orEmpty() }
                else -> null
            }
            val diff = structuredPatch?.let(::formatUnifiedDiff)
            parts[index] = existing.copy(
                tool = tool.copy(status = if (isError) "error" else "done", result = resultText, diff = diff),
            )
        }
    }

    /** Reads `total_cost_usd`/`num_turns` (top-level) and the `usage` object (input/output/cache
     * tokens) off a terminal `result` event. All fields are best-effort — Claude Code's exact usage
     * shape isn't publicly documented, so every field defaults to 0 rather than dropping the whole
     * turn's numbers when one is missing or renamed in a future CLI version. */
    private fun parseUsage(obj: JsonObject): TurnUsage {
        val usage = obj["usage"] as? JsonObject
        return TurnUsage(
            costUsd = obj.doubleOrNull("total_cost_usd") ?: 0.0,
            numTurns = obj.intOrNull("num_turns") ?: 0,
            inputTokens = usage?.intOrNull("input_tokens") ?: 0,
            outputTokens = usage?.intOrNull("output_tokens") ?: 0,
            cacheReadTokens = usage?.intOrNull("cache_read_input_tokens") ?: 0,
            cacheCreationTokens = usage?.intOrNull("cache_creation_input_tokens") ?: 0,
        )
    }

    /** Formats Claude Code's `structuredPatch` hunks (`{oldStart,oldLines,newStart,newLines,lines[]}`)
     * as a standard unified diff body, for [ToolCard] to render with +/- coloring instead of raw text. */
    private fun formatUnifiedDiff(hunks: JsonArray): String? {
        val text = hunks.mapNotNull { hunk ->
            val h = hunk as? JsonObject ?: return@mapNotNull null
            val oldStart = h.intOrNull("oldStart") ?: return@mapNotNull null
            val oldLines = h.intOrNull("oldLines") ?: 0
            val newStart = h.intOrNull("newStart") ?: return@mapNotNull null
            val newLines = h.intOrNull("newLines") ?: 0
            val lines = (h["lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            buildString {
                append("@@ -").append(oldStart).append(',').append(oldLines)
                append(" +").append(newStart).append(',').append(newLines).append(" @@\n")
                lines.forEach { appendLine(it) }
            }
        }.joinToString("\n").trim()
        return text.ifBlank { null }
    }
}

/** Safe int read for a top-level field that is expected to be a JSON number, if present. */
internal fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

/** Safe double read for a top-level field that is expected to be a JSON number, if present. */
internal fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
