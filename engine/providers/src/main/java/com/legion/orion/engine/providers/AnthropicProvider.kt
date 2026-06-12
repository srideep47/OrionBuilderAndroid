package com.legion.orion.engine.providers

import com.legion.orion.engine.providers.internal.HttpStreamException
import com.legion.orion.engine.providers.internal.postSse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Anthropic Messages API streaming. Maps text_delta → TextDelta, thinking_delta → ThinkingDelta,
 * and tool_use content blocks → ToolCall events (input_json_delta accumulates the arguments).
 * Default model id should be current, e.g. "claude-opus-4-8" or "claude-sonnet-4-6".
 */
class AnthropicProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val anthropicVersion: String = "2023-06-01",
) : ChatProvider {

    override val toolCallingMode = ToolCallingMode.NATIVE

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val headers = mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to anthropicVersion,
            "Content-Type" to "application/json",
            "Accept" to "text/event-stream",
        )
        // Map content-block index -> tool call id for the currently open tool_use block.
        val toolIdByIndex = HashMap<Int, String>()
        try {
            postSse("${baseUrl.trimEnd('/')}/v1/messages", headers, buildBody(request)).collect { sse ->
                val root = runCatching { lenientJson.parseToJsonElement(sse.data).jsonObject }.getOrNull()
                    ?: return@collect
                when (root["type"]?.jsonPrimitive?.contentOrNull) {
                    "content_block_start" -> {
                        val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val block = root["content_block"]?.jsonObject
                        if (block?.get("type")?.jsonPrimitive?.contentOrNull == "tool_use") {
                            val id = block["id"]?.jsonPrimitive?.contentOrNull ?: "toolu_$index"
                            val name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                            toolIdByIndex[index] = id
                            emit(StreamEvent.ToolCallStart(id, name))
                        }
                    }
                    "content_block_delta" -> {
                        val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val delta = root["delta"]?.jsonObject ?: return@collect
                        when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                            "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull
                                ?.let { emit(StreamEvent.TextDelta(it)) }
                            "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull
                                ?.let { emit(StreamEvent.ThinkingDelta(it)) }
                            "input_json_delta" -> {
                                val id = toolIdByIndex[index] ?: return@collect
                                delta["partial_json"]?.jsonPrimitive?.contentOrNull
                                    ?.let { emit(StreamEvent.ToolCallArgsDelta(id, it)) }
                            }
                        }
                    }
                    "content_block_stop" -> {
                        val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                        toolIdByIndex.remove(index)?.let { emit(StreamEvent.ToolCallEnd(it)) }
                    }
                    "message_delta" ->
                        root["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.intOrNull
                            ?.let { emit(StreamEvent.Usage(0, it)) }
                    "error" -> emit(
                        StreamEvent.Error(
                            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "anthropic error",
                            retryable = false,
                        ),
                    )
                }
            }
        } catch (e: HttpStreamException) {
            emit(StreamEvent.Error(e.message ?: "stream error", e.retryable))
        }
    }

    private fun buildBody(request: ChatRequest): String = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        put("max_tokens", request.maxTokens ?: 4096)
        request.temperature?.let { put("temperature", it) }
        request.system?.let { put("system", it) }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { t ->
                    addJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("input_schema", t.parameters)
                    }
                }
            }
        }
        put("messages", buildMessages(request))
    }.toString()

    private fun buildMessages(request: ChatRequest): JsonArray = buildJsonArray {
        request.messages.filter { it.role != ChatMessage.Role.SYSTEM }.forEach { m ->
            when (m.role) {
                ChatMessage.Role.USER -> addJsonObject { put("role", "user"); put("content", m.content) }
                ChatMessage.Role.TOOL -> addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", m.toolCallId ?: "")
                            put("content", m.content)
                        }
                    }
                }
                ChatMessage.Role.ASSISTANT -> addJsonObject {
                    put("role", "assistant")
                    putJsonArray("content") {
                        if (m.content.isNotEmpty()) addJsonObject { put("type", "text"); put("text", m.content) }
                        m.toolCalls.forEach { c ->
                            addJsonObject {
                                put("type", "tool_use")
                                put("id", c.id)
                                put("name", c.name)
                                put("input", runCatching { lenientJson.parseToJsonElement(c.argumentsJson) }
                                    .getOrElse { buildJsonObject { } })
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        request.assistantPrefix?.takeIf { it.isNotEmpty() }?.let { prefix ->
            addJsonObject { put("role", "assistant"); put("content", prefix) }
        }
    }
}
