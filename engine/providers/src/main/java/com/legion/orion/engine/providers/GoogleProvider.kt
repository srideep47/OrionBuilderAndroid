package com.legion.orion.engine.providers

import com.legion.orion.engine.providers.internal.HttpStreamException
import com.legion.orion.engine.providers.internal.postSse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Google Gemini streaming (generativelanguage API, streamGenerateContent?alt=sse).
 * Gemini uses roles "user"/"model"; system text goes in systemInstruction.
 */
class GoogleProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) : ChatProvider {

    override val toolCallingMode = ToolCallingMode.NATIVE

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val url = "${baseUrl.trimEnd('/')}/v1beta/models/${request.model}:streamGenerateContent?alt=sse&key=$apiKey"
        val headers = mapOf("Content-Type" to "application/json", "Accept" to "text/event-stream")
        try {
            postSse(url, headers, buildBody(request)).collect { sse ->
                val root = runCatching { lenientJson.parseToJsonElement(sse.data).jsonObject }.getOrNull()
                    ?: return@collect
                root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray
                    ?.forEach { part ->
                        part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                            ?.let { if (it.isNotEmpty()) emit(StreamEvent.TextDelta(it)) }
                    }
                root["usageMetadata"]?.jsonObject?.let { u ->
                    emit(
                        StreamEvent.Usage(
                            promptTokens = u["promptTokenCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                            completionTokens = u["candidatesTokenCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                        ),
                    )
                }
            }
        } catch (e: HttpStreamException) {
            emit(StreamEvent.Error(e.message ?: "stream error", e.retryable))
        }
    }

    private fun buildBody(request: ChatRequest): String = buildJsonObject {
        request.system?.let { sys ->
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", sys) } }
            }
        }
        put(
            "contents",
            buildJsonArray {
                request.messages.filter { it.role != ChatMessage.Role.SYSTEM }.forEach { m ->
                    addJsonObject {
                        put("role", if (m.role == ChatMessage.Role.ASSISTANT) "model" else "user")
                        putJsonArray("parts") { addJsonObject { put("text", m.content) } }
                    }
                }
                request.assistantPrefix?.takeIf { it.isNotEmpty() }?.let { prefix ->
                    addJsonObject {
                        put("role", "model")
                        putJsonArray("parts") { addJsonObject { put("text", prefix) } }
                    }
                }
            },
        )
        putJsonObject("generationConfig") {
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("maxOutputTokens", it) }
        }
    }.toString()
}
