package com.legion.orion.engine.providers

import com.legion.orion.engine.providers.internal.ThinkingSplitter
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingSplitterTest {

    private fun run(chunks: List<String>): Pair<String, String> {
        val s = ThinkingSplitter()
        val text = StringBuilder()
        val think = StringBuilder()
        val events = buildList {
            chunks.forEach { addAll(s.push(it)) }
            addAll(s.flush())
        }
        events.forEach {
            when (it) {
                is StreamEvent.TextDelta -> text.append(it.text)
                is StreamEvent.ThinkingDelta -> think.append(it.text)
                else -> {}
            }
        }
        return text.toString() to think.toString()
    }

    @Test fun plainTextNoTags() {
        val (text, think) = run(listOf("Hello ", "world"))
        assertEquals("Hello world", text)
        assertEquals("", think)
    }

    @Test fun thinkingThenText() {
        val (text, think) = run(listOf("<think>reasoning here</think>final answer"))
        assertEquals("final answer", text)
        assertEquals("reasoning here", think)
    }

    @Test fun openTagSplitAcrossChunks() {
        val (text, think) = run(listOf("<thi", "nk>secret</think>visible"))
        assertEquals("visible", text)
        assertEquals("secret", think)
    }

    @Test fun closeTagSplitAcrossChunks() {
        val (text, think) = run(listOf("<think>abc", "def</thi", "nk>tail"))
        assertEquals("tail", text)
        assertEquals("abcdef", think)
    }

    @Test fun textBeforeThinking() {
        val (text, think) = run(listOf("intro <think>mid</think> outro"))
        assertEquals("intro  outro", text)
        assertEquals("mid", think)
    }

    @Test fun unterminatedThinkingFlushedAsThinking() {
        val (text, think) = run(listOf("<think>still thinking when stream ended"))
        assertEquals("", text)
        assertEquals("still thinking when stream ended", think)
    }

    @Test fun loneAngleBracketNotHeldForever() {
        val (text, think) = run(listOf("a < b and c > d"))
        assertEquals("a < b and c > d", text)
        assertEquals("", think)
    }
}
