package com.legion.orion.p2p

import android.util.Log
import com.legion.orion.engine.providers.ChatMessage
import com.legion.orion.engine.providers.StreamEvent
import com.legion.orion.engine.providers.internal.ThinkingSplitter
import com.legion.orion.inference.EngineState
import com.legion.orion.inference.InferenceController
import com.legion.orion.infra.p2p.ComputeState
import com.legion.orion.infra.p2p.P2pController
import com.legion.orion.infra.p2p.P2pEvent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/**
 * Serves inference requests from trusted peers on the locally loaded MNN model — the mobile
 * counterpart of the desktop's compute-node. Replies use the desktop wire format: INFERENCE_CHUNK
 * data is raw OpenAI SSE event text with answer tokens in `delta.content` and thinking tokens in
 * `delta.reasoning_content`, followed by INFERENCE_DONE.
 *
 * Also keeps the bridge's 30s compute-load broadcast truthful by pushing ComputeState whenever the
 * engine loads/unloads a model.
 */
@Singleton
class P2pInferenceServer @Inject constructor(
    private val controller: P2pController,
    private val inference: InferenceController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null

    /** One generation at a time: the MNN session is single-stream. */
    private val serveMutex = Mutex()
    private val active = mutableMapOf<String, Job>()

    fun start() {
        if (collectJob != null) return
        collectJob = scope.launch {
            launch { collectRequests() }
            launch { collectCancels() }
            launch { publishComputeState() }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        scope.coroutineContext.cancelChildren()
        synchronized(active) { active.clear() }
    }

    private suspend fun collectRequests() {
        controller.inferenceRequests.collect { req ->
            val job = scope.launch { serve(req) }
            synchronized(active) { active[req.requestId] = job }
            job.invokeOnCompletion { synchronized(active) { active.remove(req.requestId) } }
        }
    }

    private suspend fun collectCancels() {
        controller.inferenceCancels.collect { cancel ->
            synchronized(active) { active[cancel.requestId] }?.cancel()
        }
    }

    private suspend fun publishComputeState() {
        inference.state.collect { state ->
            val modelName = state.modelDir?.let { File(it).name }
            controller.setCompute(
                ComputeState(
                    gpuUtilization = if (state.status == EngineState.Status.GENERATING) 1.0 else 0.0,
                    loadedModels = listOfNotNull(modelName?.takeIf { state.status.isLoaded() }),
                    computeAvailable = state.status.isLoaded(),
                    queueDepth = synchronized(active) { active.size },
                ),
            )
        }
    }

    private fun EngineState.Status.isLoaded() =
        this == EngineState.Status.READY || this == EngineState.Status.GENERATING

    private suspend fun serve(req: P2pEvent.InferenceRequested) {
        val shortId = req.requestId.take(8)
        if (!inference.isLoaded) {
            controller.respondInferenceError(
                req.peerId, req.requestId,
                "Host device has no model loaded in its Orion Engine. Ask them to load a model on the Engine screen, then retry.",
            )
            return
        }

        val parsed = parseOpenAiBody(req.body) ?: run {
            controller.respondInferenceError(req.peerId, req.requestId, "Malformed request body")
            return
        }

        Log.i(TAG, "[serve $shortId] ${parsed.messages.size} messages from peer ${req.peerId.take(16)}…")
        serveMutex.withLock {
            val modelName = inference.state.value.modelDir?.let { File(it).name } ?: "mnn-local"
            val splitter = ThinkingSplitter()
            try {
                inference.generateChat(parsed.system, parsed.messages).collect { raw ->
                    splitter.push(raw).forEach { event -> sendDelta(req, event, modelName) }
                }
                splitter.flush().forEach { event -> sendDelta(req, event, modelName) }
                controller.respondInferenceChunk(
                    req.peerId, req.requestId,
                    JsonPrimitive(finishEvent(req.requestId, modelName) + "data: [DONE]\n\n"),
                )
                controller.respondInferenceDone(req.peerId, req.requestId)
                Log.i(TAG, "[serve $shortId] completed")
            } catch (c: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "[serve $shortId] cancelled by peer")
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "[serve $shortId] failed", t)
                controller.respondInferenceError(req.peerId, req.requestId, t.message ?: "generation failed")
            }
        }
    }

    private suspend fun sendDelta(req: P2pEvent.InferenceRequested, event: StreamEvent, modelName: String) {
        val delta = when (event) {
            is StreamEvent.TextDelta -> buildJsonObject { put("content", event.text) }
            is StreamEvent.ThinkingDelta -> buildJsonObject { put("reasoning_content", event.text) }
            else -> return
        }
        val chunk = buildJsonObject {
            put("id", req.requestId)
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", modelName)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    put("delta", delta)
                    put("finish_reason", null as String?)
                }
            }
        }
        controller.respondInferenceChunk(req.peerId, req.requestId, JsonPrimitive("data: $chunk\n\n"))
    }

    private fun finishEvent(requestId: String, modelName: String): String {
        val final = buildJsonObject {
            put("id", requestId)
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", modelName)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    put("delta", buildJsonObject {})
                    put("finish_reason", "stop")
                }
            }
        }
        return "data: $final\n\n"
    }

    private data class ParsedBody(val system: String?, val messages: List<ChatMessage>)

    /** body is the raw OpenAI request: either a JSON string (desktop format) or an inline object. */
    private fun parseOpenAiBody(body: kotlinx.serialization.json.JsonElement): ParsedBody? = runCatching {
        val obj = when (body) {
            is JsonPrimitive -> json.parseToJsonElement(body.contentOrNull ?: return null).jsonObject
            else -> body.jsonObject
        }
        val rawMessages = obj["messages"] as? JsonArray ?: return null
        var system: String? = null
        val messages = mutableListOf<ChatMessage>()
        for (el in rawMessages) {
            val msg = el.jsonObject
            val role = msg["role"]?.jsonPrimitive?.contentOrNull ?: continue
            val content = msg["content"]?.jsonPrimitive?.contentOrNull ?: ""
            when (role) {
                "system" -> system = if (system == null) content else system + "\n\n" + content
                "user" -> messages += ChatMessage(ChatMessage.Role.USER, content)
                "assistant" -> messages += ChatMessage(ChatMessage.Role.ASSISTANT, content)
                "tool" -> messages += ChatMessage(ChatMessage.Role.TOOL, content)
            }
        }
        ParsedBody(system, messages)
    }.getOrNull()

    private companion object {
        const val TAG = "P2pInferenceServer"
        val json = Json { ignoreUnknownKeys = true }
    }
}
