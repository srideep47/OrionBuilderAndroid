package com.legion.orion.grok

import com.legion.orion.claudecode.doubleOrNull
import com.legion.orion.claudecode.intOrNull
import com.legion.orion.claudecode.longOrNull
import com.legion.orion.claudecode.stringOrNull
import com.legion.orion.data.chat.PartType
import com.legion.orion.data.chat.ToolView
import com.legion.orion.data.chat.UiPart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Translates Grok Build `streaming-json` NDJSON events into the same `UiPart`/`ToolView` shapes used by
 * Claude Code and Codex, so existing Compose surfaces render Grok with no new UI.
 *
 * Envelope (from Grok headless docs):
 * ```
 * {"type":"thought","data":"…"}
 * {"type":"tool_call","toolCallId":"…","toolName":"read_file","status":"in_progress","rawInput":{…}}
 * {"type":"tool_call_update","toolCallId":"…","status":"completed","rawOutput":{…}}
 * {"type":"text","data":"…"}
 * {"type":"usage",…}
 * {"type":"end","sessionId":"…","usage":{…},"total_cost_usd":0.01}
 * ```
 */
class GrokEventMapper {
    private val parts = mutableListOf<UiPart>()
    private val partIndexByToolId = mutableMapOf<String, Int>()
    private val textPartIndex: Int? get() = parts.indexOfLast { it.type == PartType.TEXT }.takeIf { it >= 0 }
    private val thinkingPartIndex: Int? get() = parts.indexOfLast { it.type == PartType.THINKING }.takeIf { it >= 0 }

    val currentParts: List<UiPart> get() = parts.toList()

    var sessionId: String? = null
        private set

    sealed interface Outcome {
        data object InProgress : Outcome
        data class TurnComplete(val resultText: String?, val isError: Boolean, val usage: TurnUsage? = null) : Outcome
        data class ProcessDied(val message: String) : Outcome
    }

    data class TurnUsage(
        val costUsd: Double = 0.0,
        val numTurns: Int = 1,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val cacheReadTokens: Int = 0,
        val cacheCreationTokens: Int = 0,
    )

    data class LiveContextUsage(
        val usedTokens: Long = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val cacheReadTokens: Long = 0,
        val contextWindowTokens: Long = 0,
    )

    var liveContextUsage: LiveContextUsage = LiveContextUsage()
        private set

    fun accept(obj: JsonObject): Outcome {
        when (obj.stringOrNull("type")) {
            "orion_error" -> return Outcome.ProcessDied(
                obj.stringOrNull("message") ?: "Grok Build ended unexpectedly",
            )

            "thought" -> {
                val chunk = obj.stringOrNull("data").orEmpty()
                if (chunk.isNotBlank()) appendThinking(chunk)
            }

            "text" -> {
                val chunk = obj.stringOrNull("data").orEmpty()
                if (chunk.isNotBlank()) appendText(chunk)
            }

            "tool_call" -> {
                val id = obj.stringOrNull("toolCallId") ?: return Outcome.InProgress
                val name = obj.stringOrNull("toolName")
                    ?: obj.stringOrNull("title")
                    ?: "tool"
                val args = obj["rawInput"]?.toString()
                    ?: obj.stringOrNull("title").orEmpty()
                upsertTool(
                    id,
                    UiPart(
                        PartType.TOOL_CALL,
                        "",
                        ToolView(
                            name = friendlyToolName(name),
                            args = args.take(4000),
                            status = "running",
                        ),
                    ),
                )
            }

            "tool_call_update" -> {
                val id = obj.stringOrNull("toolCallId") ?: return Outcome.InProgress
                val status = obj.stringOrNull("status").orEmpty()
                val isError = status.equals("failed", true) || status.equals("error", true)
                val done = status.equals("completed", true) || status.equals("done", true) || isError
                val existing = partIndexByToolId[id]?.let { parts.getOrNull(it) }
                val tool = existing?.tool
                upsertTool(
                    id,
                    UiPart(
                        PartType.TOOL_CALL,
                        "",
                        ToolView(
                            name = tool?.name ?: friendlyToolName(obj.stringOrNull("toolName") ?: "tool"),
                            args = tool?.args.orEmpty(),
                            status = when {
                                !done -> "running"
                                isError -> "error"
                                else -> "done"
                            },
                            result = if (done) {
                                obj["rawOutput"]?.toString()
                                    ?: obj.stringOrNull("message")
                                    ?: status
                            } else {
                                null
                            },
                        ),
                    ),
                )
            }

            "usage" -> {
                parseUsage(obj["usage"] as? JsonObject, cost = null)
            }

            "end" -> {
                sessionId = obj.stringOrNull("sessionId") ?: sessionId
                val usage = parseUsage(
                    obj["usage"] as? JsonObject,
                    cost = obj.doubleOrNull("total_cost_usd"),
                    numTurns = obj.intOrNull("num_turns"),
                )
                val stop = obj.stringOrNull("stopReason")
                val isError = stop == "refusal" || stop == "cancelled" ||
                    obj.stringOrNull("type") == "error"
                return Outcome.TurnComplete(lastAssistantText(), isError = isError, usage = usage)
            }

            "error" -> {
                val message = obj.stringOrNull("message")
                    ?: (obj["error"] as? JsonObject)?.stringOrNull("message")
                    ?: "Grok Build reported an error"
                return Outcome.TurnComplete(message, isError = true, usage = parseUsage(obj["usage"] as? JsonObject, null))
            }

            // plan, available_commands, etc. — ignore for UI
            else -> {}
        }
        return Outcome.InProgress
    }

    private fun appendText(chunk: String) {
        val idx = textPartIndex
        if (idx != null) {
            val prev = parts[idx]
            parts[idx] = prev.copy(text = prev.text + chunk)
        } else {
            parts.add(UiPart(PartType.TEXT, chunk))
        }
    }

    private fun appendThinking(chunk: String) {
        val idx = thinkingPartIndex
        if (idx != null) {
            val prev = parts[idx]
            parts[idx] = prev.copy(text = prev.text + chunk)
        } else {
            parts.add(UiPart(PartType.THINKING, chunk))
        }
    }

    private fun upsertTool(id: String, part: UiPart) {
        val existing = partIndexByToolId[id]
        if (existing != null && existing in parts.indices) {
            parts[existing] = part
        } else {
            parts.add(part)
            partIndexByToolId[id] = parts.lastIndex
        }
    }

    private fun lastAssistantText(): String? =
        parts.lastOrNull { it.type == PartType.TEXT }?.text?.takeIf { it.isNotBlank() }

    private fun friendlyToolName(name: String): String = when (name) {
        "run_terminal_cmd", "bash", "Bash" -> "Bash"
        "read_file", "Read" -> "Read"
        "search_replace", "Edit", "edit_file" -> "Edit"
        "write_file", "Write" -> "Write"
        "grep", "Grep" -> "Grep"
        "list_dir", "LS" -> "List"
        "web_search", "WebSearch" -> "WebSearch"
        "web_fetch", "WebFetch" -> "WebFetch"
        else -> name
    }

    private fun parseUsage(usage: JsonObject?, cost: Double?, numTurns: Int? = null): TurnUsage {
        if (usage == null && cost == null) return TurnUsage(numTurns = numTurns ?: 1)
        // Grok streams both snake_case and camelCase depending on event path (streaming-json vs ACP).
        val input = usage.intField("input_tokens", "inputTokens")
        val output = usage.intField("output_tokens", "outputTokens")
        val cacheRead = usage.intField("cache_read_input_tokens", "cachedReadTokens", "cache_read_tokens")
        val cacheCreate = usage.intField("cache_creation_input_tokens", "cacheCreationTokens")
        // Turn totals are NOT the session context window fill — only update context when the
        // event explicitly carries a context_* field (session signals remain the source of truth).
        val explicitContextUsed = usage.longField(
            "context_tokens_used", "contextTokensUsed", "context_used", "contextUsed",
        )
        val window = usage.longField("context_window", "contextWindowTokens", "context_window_tokens")
        val costFromTicks = usage?.longOrNull("costUsdTicks")?.let { it / 1_000_000_000.0 }
        liveContextUsage = liveContextUsage.copy(
            inputTokens = input.toLong(),
            outputTokens = output.toLong(),
            cacheReadTokens = cacheRead.toLong(),
            usedTokens = if (explicitContextUsed > 0) explicitContextUsed else liveContextUsage.usedTokens,
            contextWindowTokens = window.takeIf { it > 0 } ?: liveContextUsage.contextWindowTokens,
        )
        return TurnUsage(
            costUsd = cost ?: costFromTicks ?: 0.0,
            numTurns = numTurns ?: usage.intField("num_turns", "numTurns").takeIf { it > 0 } ?: 1,
            inputTokens = input,
            outputTokens = output,
            cacheReadTokens = cacheRead,
            cacheCreationTokens = cacheCreate,
        )
    }

    private fun JsonObject?.intField(vararg keys: String): Int {
        if (this == null) return 0
        for (k in keys) {
            intOrNull(k)?.let { return it }
            // some fields arrive as doubles
            longOrNull(k)?.let { return it.toInt() }
        }
        return 0
    }

    private fun JsonObject?.longField(vararg keys: String): Long {
        if (this == null) return 0L
        for (k in keys) {
            longOrNull(k)?.let { return it }
            intOrNull(k)?.let { return it.toLong() }
        }
        return 0L
    }
}
