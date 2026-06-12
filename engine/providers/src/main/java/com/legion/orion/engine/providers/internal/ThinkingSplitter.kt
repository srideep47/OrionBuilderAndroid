package com.legion.orion.engine.providers.internal

import com.legion.orion.engine.providers.StreamEvent

/**
 * Splits a raw local-model text stream into text vs. thinking, honoring `<think>`/`</think>`
 * tags that may straddle token-batch boundaries. Stateful: feed deltas via [push] in order,
 * then call [flush] once at end to release any held-back tail.
 *
 * Holds back at most (tag length - 1) trailing chars when they could be the start of a tag,
 * so a tag split across two chunks is still detected.
 */
class ThinkingSplitter {
    private val openTag = "<think>"
    private val closeTag = "</think>"
    private var inThinking = false
    private val buffer = StringBuilder()

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
        if (buffer.isNotEmpty()) {
            val rest = buffer.toString()
            buffer.setLength(0)
            out += if (inThinking) StreamEvent.ThinkingDelta(rest) else StreamEvent.TextDelta(rest)
        }
        return out
    }

    private fun drain(out: MutableList<StreamEvent>, finalFlush: Boolean) {
        while (true) {
            if (!inThinking) {
                val idx = buffer.indexOf(openTag)
                if (idx >= 0) {
                    if (idx > 0) out += StreamEvent.TextDelta(buffer.substring(0, idx))
                    buffer.delete(0, idx + openTag.length)
                    inThinking = true
                    continue
                }
                if (!emitSafe(out, openTag, finalFlush) { StreamEvent.TextDelta(it) }) break
            } else {
                val idx = buffer.indexOf(closeTag)
                if (idx >= 0) {
                    if (idx > 0) out += StreamEvent.ThinkingDelta(buffer.substring(0, idx))
                    buffer.delete(0, idx + closeTag.length)
                    inThinking = false
                    continue
                }
                if (!emitSafe(out, closeTag, finalFlush) { StreamEvent.ThinkingDelta(it) }) break
            }
        }
    }

    /**
     * Emits the part of the buffer that can't be the start of [tag], keeping a possible partial
     * tag suffix for the next push. Returns false (stop looping) once it has emitted/held.
     */
    private inline fun emitSafe(
        out: MutableList<StreamEvent>,
        tag: String,
        finalFlush: Boolean,
        make: (String) -> StreamEvent,
    ): Boolean {
        if (buffer.isEmpty()) return false
        val hold = if (finalFlush) 0 else longestTagPrefixSuffix(buffer, tag)
        val emitLen = buffer.length - hold
        if (emitLen > 0) {
            out += make(buffer.substring(0, emitLen))
            buffer.delete(0, emitLen)
        }
        return false
    }

    /** Largest k (< tag.length) such that buffer's last k chars equal tag's first k chars. */
    private fun longestTagPrefixSuffix(buffer: CharSequence, tag: String): Int {
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
