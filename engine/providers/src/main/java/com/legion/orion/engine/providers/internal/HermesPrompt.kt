package com.legion.orion.engine.providers.internal

import com.legion.orion.engine.providers.ChatMessage
import com.legion.orion.engine.providers.ToolSchema

/**
 * Hermes/Qwen tool-calling prompt conventions for local models: a `<tools>` system block listing
 * function signatures, assistant tool calls rendered as `<tool_call>` blocks, and tool results
 * fed back as `<tool_response>` inside a user turn.
 */
object HermesPrompt {

    fun toolsSystemBlock(tools: List<ToolSchema>): String = buildString {
        appendLine("# Tools")
        appendLine()
        appendLine("You may call one or more functions to assist with the user query.")
        appendLine("You are provided with function signatures within <tools></tools> XML tags:")
        appendLine("<tools>")
        tools.forEach { t ->
            // One JSON function signature per line (Hermes format).
            appendLine("""{"type":"function","function":{"name":"${t.name}","description":${jsonString(t.description)},"parameters":${t.parameters}}}""")
        }
        appendLine("</tools>")
        appendLine()
        appendLine("For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:")
        appendLine("<tool_call>")
        appendLine("""{"name": <function-name>, "arguments": <args-json-object>}""")
        append("</tool_call>")
    }

    /**
     * Collapses tool calls/results into text so the conversation can be sent as plain
     * (role, content) pairs to the MNN session. Assistant tool calls become `<tool_call>` blocks;
     * TOOL messages become a USER turn wrapping `<tool_response>`.
     */
    fun renderForLocal(messages: List<ChatMessage>): List<ChatMessage> = messages.map { m ->
        when {
            m.role == ChatMessage.Role.ASSISTANT && m.toolCalls.isNotEmpty() -> {
                val calls = m.toolCalls.joinToString("\n") { c ->
                    "<tool_call>\n{\"name\": \"${c.name}\", \"arguments\": ${c.argumentsJson}}\n</tool_call>"
                }
                m.copy(content = if (m.content.isBlank()) calls else "${m.content}\n$calls", toolCalls = emptyList())
            }
            m.role == ChatMessage.Role.TOOL ->
                ChatMessage(
                    role = ChatMessage.Role.USER,
                    content = "<tool_response>\n${m.content}\n</tool_response>",
                )
            else -> m
        }
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
