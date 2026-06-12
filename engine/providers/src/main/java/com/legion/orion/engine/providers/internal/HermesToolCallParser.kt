package com.legion.orion.engine.providers.internal

import com.legion.orion.engine.providers.StreamEvent
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Streaming parser for Qwen/Hermes-style local output. Routes `<think>…</think>` to thinking,
 * `<tool_call>{...}</tool_call>` to tool-call events (with JSON repair), and everything else to
 * text. Tolerant of tags split across token batches (holds back a partial-tag suffix) and of
 * fenced/bare JSON inside tool calls.
 *
 * Tool calls are buffered fully then emitted as ToolCallStart + ToolCallArgsDelta(full) +
 * ToolCallEnd, since local generation gives us the whole block before we need to act on it.
 */
class HermesToolCallParser {
    private enum class State { TEXT, THINKING, TOOLCALL }

    private val openThink = "<think>"
    private val closeThink = "</think>"
    private val openTool = "<tool_call>"
    private val closeTool = "</tool_call>"

    private var state = State.TEXT
    private val buffer = StringBuilder()
    private val toolBuf = StringBuilder()
    private var toolSeq = 0

    fun push(text: String): List<StreamEvent> {
        if (text.isEmpty()) return emptyList()
        buffer.append(text)
        val out = ArrayList<StreamEvent>()
        drain(out, finalFlush = false)
        return out
    }

    fun flush(): List<StreamEvent> {
        val out = ArrayList<StreamEvent>()
        drain(out, finalFlush = true)
        // Release any remaining buffered text/thinking; an unterminated tool_call is dropped
        // (it never completed, so acting on it would be unsafe).
        if (buffer.isNotEmpty()) {
            val rest = buffer.toString()
            buffer.setLength(0)
            when (state) {
                State.THINKING -> out += StreamEvent.ThinkingDelta(rest)
                State.TEXT -> out += StreamEvent.TextDelta(rest)
                State.TOOLCALL -> { /* incomplete tool call discarded */ }
            }
        }
        return out
    }

    private fun drain(out: MutableList<StreamEvent>, finalFlush: Boolean) {
        while (true) {
            when (state) {
                State.TEXT -> {
                    val think = buffer.indexOf(openThink)
                    val tool = buffer.indexOf(openTool)
                    val next = earliest(think, tool)
                    if (next < 0) {
                        if (!emitSafe(out, listOf(openThink, openTool), finalFlush) { StreamEvent.TextDelta(it) }) return
                    } else {
                        if (next > 0) out += StreamEvent.TextDelta(buffer.substring(0, next))
                        if (next == think) {
                            buffer.delete(0, think + openThink.length); state = State.THINKING
                        } else {
                            buffer.delete(0, tool + openTool.length); state = State.TOOLCALL
                        }
                    }
                }
                State.THINKING -> {
                    val idx = buffer.indexOf(closeThink)
                    if (idx >= 0) {
                        if (idx > 0) out += StreamEvent.ThinkingDelta(buffer.substring(0, idx))
                        buffer.delete(0, idx + closeThink.length); state = State.TEXT
                    } else {
                        if (!emitSafe(out, listOf(closeThink), finalFlush) { StreamEvent.ThinkingDelta(it) }) return
                    }
                }
                State.TOOLCALL -> {
                    val idx = buffer.indexOf(closeTool)
                    if (idx >= 0) {
                        toolBuf.append(buffer.substring(0, idx))
                        buffer.delete(0, idx + closeTool.length)
                        emitToolCall(out, toolBuf.toString())
                        toolBuf.setLength(0)
                        state = State.TEXT
                    } else {
                        // Accumulate, but keep a possible partial "</tool_call>" suffix in buffer.
                        val hold = if (finalFlush) 0 else longestPrefixSuffix(buffer, closeTool)
                        val take = buffer.length - hold
                        if (take > 0) { toolBuf.append(buffer.substring(0, take)); buffer.delete(0, take) }
                        return
                    }
                }
            }
        }
    }

    private fun emitToolCall(out: MutableList<StreamEvent>, raw: String) {
        val element = JsonRepair.parse(raw) ?: return // unparseable → drop (agent will see no call)
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return
        val argsElement = obj["arguments"] ?: obj["parameters"]
        val argsJson = argsElement?.toString() ?: "{}"
        val id = "local_call_${toolSeq++}"
        out += StreamEvent.ToolCallStart(id, name)
        out += StreamEvent.ToolCallArgsDelta(id, argsJson)
        out += StreamEvent.ToolCallEnd(id)
    }

    /** Emits buffer minus a held-back partial-tag suffix (across all [tags]); returns false to stop. */
    private inline fun emitSafe(
        out: MutableList<StreamEvent>,
        tags: List<String>,
        finalFlush: Boolean,
        make: (String) -> StreamEvent,
    ): Boolean {
        if (buffer.isEmpty()) return false
        val hold = if (finalFlush) 0 else tags.maxOf { longestPrefixSuffix(buffer, it) }
        val emitLen = buffer.length - hold
        if (emitLen > 0) {
            out += make(buffer.substring(0, emitLen))
            buffer.delete(0, emitLen)
        }
        return false
    }

    private fun earliest(a: Int, b: Int): Int = when {
        a < 0 -> b
        b < 0 -> a
        else -> minOf(a, b)
    }

    /** Largest k (< tag.length) such that buffer's last k chars equal tag's first k chars. */
    private fun longestPrefixSuffix(buffer: CharSequence, tag: String): Int {
        val max = minOf(buffer.length, tag.length - 1)
        for (k in max downTo 1) {
            var match = true
            for (i in 0 until k) {
                if (buffer[buffer.length - k + i] != tag[i]) { match = false; break }
            }
            if (match) return k
        }
        return 0
    }
}
