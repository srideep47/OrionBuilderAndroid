package com.legion.orion.p2p

import com.legion.orion.engine.providers.ChatProvider
import com.legion.orion.engine.providers.ChatMessage
import com.legion.orion.engine.providers.ChatRequest
import com.legion.orion.engine.providers.StreamEvent
import com.legion.orion.engine.providers.ToolCallingMode
import com.legion.orion.engine.providers.internal.HermesPrompt
import com.legion.orion.engine.providers.internal.HermesToolCallParser
import com.legion.orion.infra.p2p.P2pController
import com.legion.orion.infra.p2p.P2pEvent
import java.util.UUID
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Runs a chat completion on a trusted peer's loaded model over the P2P channel — the mobile
 * counterpart of the desktop's compute-proxy. The wire format matches the desktop exactly:
 * INFERENCE_REQUEST.body is a raw OpenAI /v1/chat/completions request string, and replies arrive
 * as INFERENCE_CHUNK messages whose data is raw SSE event text (delta.content for answer tokens,
 * delta.reasoning_content for thinking tokens).
 *
 * Tool calling is PROMPT_FORMAT (Hermes `<tools>` block + `<tool_call>` text parsing): peers serve
 * local-grade models whose native tool support can't be assumed, and plain text survives any
 * peer-side engine (phone MNN or desktop llama-server) unchanged.
 */
class PeerChatProvider(
    private val peerId: String,
    private val controller: P2pController,
) : ChatProvider {

    override val toolCallingMode = ToolCallingMode.PROMPT_FORMAT

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val requestId = UUID.randomUUID().toString()
        val incoming = Channel<P2pEvent>(Channel.BUFFERED)

        coroutineScope {
            val relay = launch {
                controller.inferenceReplies.collect { event ->
                    val matches = when (event) {
                        is P2pEvent.InferenceChunk -> event.requestId == requestId
                        is P2pEvent.InferenceDone -> event.requestId == requestId
                        is P2pEvent.InferenceError -> event.requestId == requestId
                        else -> false
                    }
                    if (matches) incoming.send(event)
                }
            }

            var done = false
            try {
                controller.requestInference(peerId, requestId, JsonPrimitive(buildBody(request)))

                val parser = HermesToolCallParser()
                while (!done) {
                    val event = try {
                        withTimeout(CHUNK_TIMEOUT_MS) { incoming.receive() }
                    } catch (t: TimeoutCancellationException) {
                        emit(StreamEvent.Error("Peer stopped responding (no data for ${CHUNK_TIMEOUT_MS / 1000}s)", retryable = true))
                        break
                    }
                    when (event) {
                        is P2pEvent.InferenceChunk -> {
                            val (text, thinking) = parseSseChunk(event.data)
                            if (thinking.isNotEmpty()) emit(StreamEvent.ThinkingDelta(thinking))
                            if (text.isNotEmpty()) parser.push(text).forEach { emit(it) }
                        }
                        is P2pEvent.InferenceDone -> {
                            parser.flush().forEach { emit(it) }
                            done = true
                        }
                        is P2pEvent.InferenceError -> {
                            emit(StreamEvent.Error(event.error, retryable = false))
                            done = true
                        }
                        else -> Unit
                    }
                }
            } finally {
                relay.cancel()
                if (!done) {
                    // Stream ended early (user stop / timeout) — tell the peer to stop generating.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching { controller.cancelInference(peerId, requestId) }
                    }
                }
            }
        }
    }

    /** OpenAI /v1/chat/completions request body, matching what the desktop proxy forwards. */
    private fun buildBody(request: ChatRequest): String {
        val system = if (request.tools.isNotEmpty()) {
            buildString {
                request.system?.let { append(it); append("\n\n") }
                append(HermesPrompt.toolsSystemBlock(request.tools))
            }
        } else {
            request.system
        }
        val rendered = HermesPrompt.renderForLocal(request.messages)
        val body = buildJsonObject {
            put("model", request.model.ifBlank { "remote-peer" })
            put("stream", true)
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }
            put("messages", buildJsonArray {
                system?.takeIf { it.isNotBlank() }?.let {
                    add(buildJsonObject { put("role", "system"); put("content", it) })
                }
                rendered.filter { it.role != ChatMessage.Role.SYSTEM }.forEach { msg ->
                    add(buildJsonObject {
                        put("role", msg.role.name.lowercase())
                        put("content", msg.content)
                    })
                }
            })
        }
        return body.toString()
    }

    /**
     * Extracts (content, reasoning) deltas from raw SSE event text. A single INFERENCE_CHUNK may
     * carry multiple `data:` lines; malformed lines are skipped rather than failing the stream.
     */
    private fun parseSseChunk(data: kotlinx.serialization.json.JsonElement): Pair<String, String> {
        val raw = (data as? JsonPrimitive)?.contentOrNull ?: return "" to ""
        val text = StringBuilder()
        val thinking = StringBuilder()
        for (line in raw.split("\n")) {
            if (!line.startsWith("data: ")) continue
            val payload = line.removePrefix("data: ").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val delta = runCatching {
                json.parseToJsonElement(payload).jsonObject["choices"]
                    ?.let { it as? kotlinx.serialization.json.JsonArray }
                    ?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
            }.getOrNull() ?: continue
            (delta["content"] as? JsonPrimitive)?.contentOrNull?.let { text.append(it) }
            (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull?.let { thinking.append(it) }
        }
        return text.toString() to thinking.toString()
    }

    private companion object {
        const val CHUNK_TIMEOUT_MS = 120_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}
