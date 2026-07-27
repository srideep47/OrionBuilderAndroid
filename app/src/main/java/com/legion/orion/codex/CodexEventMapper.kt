package com.legion.orion.codex

import com.legion.orion.claudecode.intOrNull
import com.legion.orion.claudecode.longOrNull
import com.legion.orion.claudecode.stringOrNull
import com.legion.orion.data.chat.PartType
import com.legion.orion.data.chat.ToolView
import com.legion.orion.data.chat.UiPart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Translates one Codex turn's `codex exec --json` JSONL events into the same `UiPart`/`ToolView` shapes
 * the Claude Code path and the on-device BUILD agent already produce, so every existing Compose surface
 * — `ToolCard`, `ThinkingBlock`, `DiffView`, markdown text — renders Codex with no new UI.
 *
 * Deliberately mirrors [com.legion.orion.claudecode.StreamJsonEventMapper]'s public contract
 * ([Outcome], [TurnUsage], `currentParts`) so `ChatViewModel`'s two engine paths stay structurally
 * identical and a change to one is obvious when it isn't made to the other.
 *
 * ## Envelope
 * ```
 * {"type":"thread.started","thread_id":"0199a213-…"}
 * {"type":"turn.started"}
 * {"type":"item.started","item":{"id":"item_1","type":"command_execution"}}
 * {"type":"item.completed","item":{"id":"item_3","type":"agent_message"}}
 * {"type":"turn.completed","usage":{"input_tokens":24763,"cached_input_tokens":24448}}
 * ```
 *
 * ## The one real difference from Claude Code, and it is user-visible
 * Claude streams token-level deltas (`content_block_delta`), so its text types out live. Codex reports
 * whole **items**: an `agent_message` arrives complete, at `item.completed`. So Codex replies appear
 * paragraph-at-a-time rather than word-at-a-word. `item.started` is used to place a running tool card
 * immediately, which keeps long shell commands from looking frozen, but there is no way to fake
 * token-level typing for text — that data simply isn't in the stream. This is a property of the CLI, not
 * something to fix here.
 *
 * ## Parsing posture
 * Codex's JSON event output is explicitly experimental and the item field names are not contractual.
 * Every read is therefore best-effort and every unknown item type degrades to a generic tool card rather
 * than being dropped: a renamed field should cost one detail in one card, never a blank turn. The same
 * discipline the Claude mapper arrived at empirically.
 */
class CodexEventMapper {
    private val parts = mutableListOf<UiPart>()

    /** Codex item id → index into [parts], so a later `item.completed` updates the card that
     * `item.started` created instead of appending a duplicate. */
    private val partIndexByItemId = mutableMapOf<String, Int>()

    val currentParts: List<UiPart> get() = parts.toList()

    var threadId: String? = null
        private set

    sealed interface Outcome {
        data object InProgress : Outcome
        data class TurnComplete(val resultText: String?, val isError: Boolean, val usage: TurnUsage? = null) : Outcome
        data class ProcessDied(val message: String) : Outcome
    }

    /**
     * Token accounting from `turn.completed`.
     *
     * Note [costUsd] is always 0.0: Codex reports tokens but not a dollar figure, whereas Claude Code
     * reports `total_cost_usd` directly. The field is kept so the shared stats strip has one shape to
     * render, and the Codex UI shows token counts instead of a cost.
     */
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

    /** Feeds one raw event; mutates [currentParts] in place. Returns the turn's status. */
    fun accept(obj: JsonObject): Outcome {
        when (obj.stringOrNull("type")) {
            "orion_error" -> return Outcome.ProcessDied(
                obj.stringOrNull("message") ?: "Codex ended unexpectedly",
            )

            "thread.started" -> threadId = obj.stringOrNull("thread_id") ?: threadId

            "item.started", "item.updated", "item.completed" -> {
                val item = obj["item"] as? JsonObject ?: return Outcome.InProgress
                handleItem(item, completed = obj.stringOrNull("type") == "item.completed")
            }

            "turn.completed" -> {
                val usage = parseUsage(obj["usage"] as? JsonObject)
                return Outcome.TurnComplete(lastAgentMessage(), isError = false, usage = usage)
            }

            "turn.failed" -> {
                val message = (obj["error"] as? JsonObject)?.stringOrNull("message")
                    ?: obj.stringOrNull("message")
                    ?: "Codex reported a failed turn"
                return Outcome.TurnComplete(message, isError = true, usage = parseUsage(obj["usage"] as? JsonObject))
            }

            "error" -> {
                val message = obj.stringOrNull("message")
                    ?: (obj["error"] as? JsonObject)?.stringOrNull("message")
                    ?: "Codex reported an error"
                return Outcome.TurnComplete(message, isError = true)
            }

            // turn.started carries nothing to render; unknown envelope types are ignored rather than
            // treated as failures, since new event types are additive.
            else -> {}
        }
        return Outcome.InProgress
    }

    private fun handleItem(item: JsonObject, completed: Boolean) {
        val id = item.stringOrNull("id") ?: return
        when (val itemType = item.stringOrNull("type")) {
            "agent_message" -> upsertPart(id, UiPart(PartType.TEXT, itemText(item)))

            "reasoning" -> {
                val text = itemText(item)
                // With show_raw_agent_reasoning on, reasoning items carry real text; if a release stops
                // providing it, drop the block rather than rendering an empty thinking bubble.
                if (text.isNotBlank()) upsertPart(id, UiPart(PartType.THINKING, text))
            }

            "todo_list" -> {
                val text = formatTodoList(item)
                if (text.isNotBlank()) upsertPart(id, UiPart(PartType.TEXT, text))
            }

            "command_execution" -> {
                val command = item.stringOrNull("command")
                    ?: (item["command"] as? JsonArray)?.joinToString(" ") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
                    ?: ""
                val exitCode = item.intOrNull("exit_code")
                upsertPart(
                    id,
                    UiPart(
                        PartType.TOOL_CALL,
                        "",
                        ToolView(
                            // "Bash" rather than "command_execution": the tool cards sit next to Claude
                            // Code's in the same history, and a user should not have to learn two names
                            // for the same thing.
                            name = "Bash",
                            args = command,
                            status = toolStatus(completed, isError = exitCode != null && exitCode != 0),
                            result = if (completed) commandOutput(item) else null,
                        ),
                    ),
                )
            }

            "file_change" -> {
                val paths = filePaths(item)
                upsertPart(
                    id,
                    UiPart(
                        PartType.TOOL_CALL,
                        "",
                        ToolView(
                            name = "Edit",
                            args = paths,
                            status = toolStatus(completed, isError = false),
                            result = if (completed) item.stringOrNull("status") ?: "applied" else null,
                            diff = item.stringOrNull("diff") ?: item.stringOrNull("unified_diff"),
                        ),
                    ),
                )
            }

            "mcp_tool_call" -> {
                val server = item.stringOrNull("server").orEmpty()
                val tool = item.stringOrNull("tool") ?: item.stringOrNull("name").orEmpty()
                upsertPart(
                    id,
                    UiPart(
                        PartType.TOOL_CALL,
                        "",
                        ToolView(
                            name = listOf(server, tool).filter { it.isNotBlank() }.joinToString(":").ifBlank { "MCP tool" },
                            args = item["arguments"]?.toString().orEmpty(),
                            status = toolStatus(completed, isError = item.stringOrNull("status") == "failed"),
                            result = if (completed) item["result"]?.toString() else null,
                        ),
                    ),
                )
            }

            "web_search" -> upsertPart(
                id,
                UiPart(
                    PartType.TOOL_CALL,
                    "",
                    ToolView(
                        name = "WebSearch",
                        args = item.stringOrNull("query").orEmpty(),
                        status = toolStatus(completed, isError = false),
                    ),
                ),
            )

            // An unrecognised item still gets a card carrying its raw JSON. That is deliberately ugly:
            // it makes a schema change visible to whoever is looking at the screen instead of silently
            // swallowing a step the agent actually took.
            else -> upsertPart(
                id,
                UiPart(
                    PartType.TOOL_CALL,
                    "",
                    ToolView(
                        name = itemType ?: "item",
                        args = item.toString().take(2000),
                        status = toolStatus(completed, isError = false),
                    ),
                ),
            )
        }
    }

    private fun toolStatus(completed: Boolean, isError: Boolean): String = when {
        !completed -> "running"
        isError -> "error"
        else -> "done"
    }

    /** Adds the part on first sight of an item id, replaces it in place on every later update. */
    private fun upsertPart(itemId: String, part: UiPart) {
        val existing = partIndexByItemId[itemId]
        if (existing != null && existing in parts.indices) {
            parts[existing] = part
        } else {
            parts.add(part)
            partIndexByItemId[itemId] = parts.lastIndex
        }
    }

    /** Codex has used more than one field name for an item's text across releases; accept any of them
     * rather than betting on one. */
    private fun itemText(item: JsonObject): String =
        item.stringOrNull("text")
            ?: item.stringOrNull("content")
            ?: item.stringOrNull("summary")
            ?: (item["content"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.stringOrNull("text") }
                ?.joinToString("\n")
                .orEmpty()

    private fun commandOutput(item: JsonObject): String? {
        val output = item.stringOrNull("aggregated_output")
            ?: item.stringOrNull("output")
            ?: item.stringOrNull("stdout")
        val exit = item.intOrNull("exit_code")
        return when {
            output.isNullOrBlank() && exit == null -> null
            output.isNullOrBlank() -> "exit code $exit"
            exit == null || exit == 0 -> output
            else -> "$output\n(exit code $exit)"
        }
    }

    private fun filePaths(item: JsonObject): String {
        (item["changes"] as? JsonArray)?.let { changes ->
            val names = changes.mapNotNull { (it as? JsonObject)?.stringOrNull("path") }
            if (names.isNotEmpty()) return names.joinToString(", ")
        }
        return item.stringOrNull("path") ?: item.stringOrNull("file").orEmpty()
    }

    private fun formatTodoList(item: JsonObject): String {
        val items = item["items"] as? JsonArray ?: return ""
        return items.mapNotNull { entry ->
            val todo = entry as? JsonObject ?: return@mapNotNull null
            val text = todo.stringOrNull("text") ?: todo.stringOrNull("title") ?: return@mapNotNull null
            val done = (todo["completed"] as? JsonPrimitive)?.contentOrNull == "true" ||
                todo.stringOrNull("status") == "completed"
            if (done) "- [x] $text" else "- [ ] $text"
        }.joinToString("\n")
    }

    /** The turn's final assistant text, for the plain-text copy `ChatViewModel` keeps for context and
     * for auto-titling. Codex's `turn.completed` carries no result string of its own, unlike Claude's
     * `result` event, so it's recovered from the last agent_message part. */
    private fun lastAgentMessage(): String? =
        parts.lastOrNull { it.type == PartType.TEXT }?.text?.takeIf { it.isNotBlank() }

    private fun parseUsage(usage: JsonObject?): TurnUsage {
        if (usage == null) return TurnUsage()
        val input = usage.intOrNull("input_tokens") ?: 0
        val cached = usage.intOrNull("cached_input_tokens") ?: 0
        val output = usage.intOrNull("output_tokens") ?: 0
        liveContextUsage = liveContextUsage.copy(
            inputTokens = input.toLong(),
            outputTokens = output.toLong(),
            cacheReadTokens = cached.toLong(),
            // Codex counts cached input inside input_tokens, so adding them would double-count the
            // cached portion — unlike Claude, where cache reads are reported as a separate bucket.
            usedTokens = (input + output).toLong(),
            contextWindowTokens = usage.longOrNull("context_window") ?: liveContextUsage.contextWindowTokens,
        )
        return TurnUsage(
            inputTokens = input,
            outputTokens = output,
            cacheReadTokens = cached,
        )
    }
}
