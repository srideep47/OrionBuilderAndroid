package com.legion.orion.engine.providers

import com.legion.orion.engine.providers.internal.HermesToolCallParser
import com.legion.orion.engine.providers.internal.JsonRepair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesToolCallParserTest {

    private data class Parsed(
        val text: String,
        val thinking: String,
        val calls: List<Pair<String, String>>, // name to argsJson
    )

    private fun run(chunks: List<String>): Parsed {
        val p = HermesToolCallParser()
        val text = StringBuilder()
        val think = StringBuilder()
        val calls = mutableListOf<Pair<String, String>>()
        var pendingName: String? = null
        val events = buildList {
            chunks.forEach { addAll(p.push(it)) }
            addAll(p.flush())
        }
        events.forEach { e ->
            when (e) {
                is StreamEvent.TextDelta -> text.append(e.text)
                is StreamEvent.ThinkingDelta -> think.append(e.text)
                is StreamEvent.ToolCallStart -> pendingName = e.name
                is StreamEvent.ToolCallArgsDelta -> calls.add((pendingName ?: "?") to e.jsonChunk)
                else -> {}
            }
        }
        return Parsed(text.toString(), think.toString(), calls)
    }

    @Test fun plainText() {
        val r = run(listOf("Hello ", "there"))
        assertEquals("Hello there", r.text)
        assertTrue(r.calls.isEmpty())
    }

    @Test fun singleToolCall() {
        val r = run(listOf("""<tool_call>{"name": "read_file", "arguments": {"path": "a.kt"}}</tool_call>"""))
        assertEquals(1, r.calls.size)
        assertEquals("read_file", r.calls[0].first)
        assertTrue(r.calls[0].second.contains("a.kt"))
    }

    @Test fun toolCallSplitAcrossChunks() {
        val r = run(listOf("<tool_", "call>{\"name\": \"grep\", \"arg", "uments\": {\"q\": \"foo\"}}</tool_", "call>"))
        assertEquals(1, r.calls.size)
        assertEquals("grep", r.calls[0].first)
    }

    @Test fun textThenToolCall() {
        val r = run(listOf("Let me read that.\n", """<tool_call>{"name":"read_file","arguments":{"path":"x"}}</tool_call>"""))
        assertEquals("Let me read that.\n", r.text)
        assertEquals("read_file", r.calls[0].first)
    }

    @Test fun thinkingThenToolCall() {
        val r = run(listOf("<think>I should read it</think>", """<tool_call>{"name":"read_file","arguments":{"path":"x"}}</tool_call>"""))
        assertEquals("I should read it", r.thinking)
        assertEquals(1, r.calls.size)
    }

    @Test fun fencedJsonToolCall() {
        val r = run(listOf("<tool_call>\n```json\n{\"name\": \"list_files\", \"arguments\": {}}\n```\n</tool_call>"))
        assertEquals(1, r.calls.size)
        assertEquals("list_files", r.calls[0].first)
    }

    @Test fun unterminatedToolCallDropped() {
        val r = run(listOf("""<tool_call>{"name":"read_file","arguments":{"path":"x"}}"""))
        assertTrue("incomplete tool call must not be emitted", r.calls.isEmpty())
    }

    // ── JsonRepair ──

    @Test fun repairTrailingComma() {
        val el = JsonRepair.parse("""{"a": 1, "b": 2,}""")
        assertNotNull(el)
    }

    @Test fun repairUnquotedKeys() {
        val el = JsonRepair.parse("""{name: "read_file", arguments: {path: "a.kt"}}""")
        assertNotNull(el)
    }

    @Test fun repairFencedAndProse() {
        val el = JsonRepair.parse("Sure! ```json\n{\"x\": 1}\n``` done")
        assertNotNull(el)
    }

    @Test fun unrepairableReturnsNull() {
        assertNull(JsonRepair.parse("this is not json at all <<<"))
    }
}
