package com.legion.orion.engine.providers

import com.legion.orion.engine.providers.internal.HttpStreamException
import com.legion.orion.engine.providers.internal.postSse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * OpenAI Chat Completions streaming. Also covers any openai-compatible endpoint (Azure, xAI,
 * Ollama, LM Studio, vLLM) via [baseUrl]. Supports native tool calling and reads
 * `delta.reasoning_content` (DeepSeek-R1 / Qwen thinking on compat servers) as thinking.
 */
class OpenAiProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val extraHeaders: Map<String, String> = emptyMap(),
) : ChatProvider {

    override val toolCallingMode = ToolCallingMode.NATIVE

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            put("Content-Type", "application/json")
            put("Accept", "text/event-stream")
            putAll(extraHeaders)
        }
        // Track streamed tool calls by their position index in the delta array.
        val toolIdByIndex = HashMap<Int, String>()
        val toolStarted = HashSet<Int>()
        var sawToolCalls = false
        try {
            postSse("${baseUrl.trimEnd('/')}/chat/completions", headers, buildBody(request)).collect { sse ->
                val payload = sse.data
                if (payload == "[DONE]") return@collect
                val root = runCatching { lenientJson.parseToJsonElement(payload).jsonObject }.getOrNull()
                    ?: return@collect
                val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                val delta = choice?.get("delta")?.jsonObject
                delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                    ?.let { if (it.isNotEmpty()) emit(StreamEvent.ThinkingDelta(it)) }
                delta?.get("content")?.jsonPrimitive?.contentOrNull
                    ?.let { if (it.isNotEmpty()) emit(StreamEvent.TextDelta(it)) }

                delta?.get("tool_calls")?.jsonArray?.forEach { tcEl ->
                    val tc = tcEl.jsonObject
                    val index = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val fn = tc["function"]?.jsonObject
                    val name = fn?.get("name")?.jsonPrimitive?.contentOrNull
                    val id = tc["id"]?.jsonPrimitive?.contentOrNull ?: toolIdByIndex[index] ?: "call_$index"
                    toolIdByIndex[index] = id
                    if (index !in toolStarted && name != null) {
                        toolStarted += index
                        sawToolCalls = true
                        emit(StreamEvent.ToolCallStart(id, name))
                    }
                    fn?.get("arguments")?.jsonPrimitive?.contentOrNull
                        ?.let { if (it.isNotEmpty()) emit(StreamEvent.ToolCallArgsDelta(id, it)) }
                }

                val finish = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
                if (finish == "tool_calls") {
                    toolStarted.forEach { idx -> toolIdByIndex[idx]?.let { emit(StreamEvent.ToolCallEnd(it)) } }
                }

                root["usage"]?.jsonObject?.let { u ->
                    emit(
                        StreamEvent.Usage(
                            promptTokens = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                            completionTokens = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                        ),
                    )
                }
            }
            // Safety net: close any tool calls that didn't get an explicit finish_reason.
            if (sawToolCalls) toolStarted.forEach { idx -> toolIdByIndex[idx]?.let { emit(StreamEvent.ToolCallEnd(it)) } }
        } catch (e: HttpStreamException) {
            emit(StreamEvent.Error(e.message ?: "stream error", e.retryable))
        }
    }

    private fun buildBody(request: ChatRequest): String = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        request.temperature?.let { put("temperature", it) }
        request.maxTokens?.let { put("max_tokens", it) }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { t ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parameters)
                        }
                    }
                }
            }
        }
        put("messages", buildMessages(request))
    }.toString()

    private fun buildMessages(request: ChatRequest): JsonArray = buildJsonArray {
        request.system?.let { sys -> addJsonObject { put("role", "system"); put("content", sys) } }
        request.messages.forEach { m ->
            when (m.role) {
                ChatMessage.Role.SYSTEM -> addJsonObject { put("role", "system"); put("content", m.content) }
                ChatMessage.Role.USER -> addJsonObject { put("role", "user"); put("content", m.content) }
                ChatMessage.Role.TOOL -> addJsonObject {
                    put("role", "tool")
                    put("tool_call_id", m.toolCallId ?: "")
                    put("content", m.content)
                }
                ChatMessage.Role.ASSISTANT -> addJsonObject {
                    put("role", "assistant")
                    if (m.content.isNotEmpty()) put("content", m.content) else put("content", JsonNull)
                    if (m.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            m.toolCalls.forEach { c ->
                                addJsonObject {
                                    put("id", c.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", c.name)
                                        put("arguments", c.argumentsJson)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        request.assistantPrefix?.takeIf { it.isNotEmpty() }?.let { prefix ->
            addJsonObject { put("role", "assistant"); put("content", prefix) }
        }
    }
}
