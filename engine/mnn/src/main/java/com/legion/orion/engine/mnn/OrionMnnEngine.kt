// Clean coroutine/Flow facade over the ported GPTee MNN session (LlmSession + ChatService).
// This is the single entry point the app/InferenceService uses; the com.localai.* classes
// below it stay verbatim from GPTee so JNI symbols and behavior are unchanged.
package com.legion.orion.engine.mnn

import android.util.Log
import com.localai.llm.ChatService
import com.localai.llm.GenerateProgressListener
import com.localai.llm.LlmSession
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MNN backend identifiers as understood by the model config (`backend_type`).
 * The fallback chain CPU -> OpenCL -> Vulkan -> QNN is enforced one level up.
 */
enum class MnnBackend(val configValue: String) {
    CPU("cpu"),
    OPENCL("opencl"),
    VULKAN("vulkan"),
    QNN("qnn"),
}

/** A token (or token batch) emitted during generation. */
data class GenerationChunk(val text: String)

/**
 * Owns a single loaded MNN model. Not thread-safe across concurrent [generate] calls — the
 * InferenceService serializes access. One engine instance == one loaded model.
 */
class OrionMnnEngine {

    private val chatService = ChatService.provide()

    @Volatile
    private var session: LlmSession? = null

    @Volatile
    var loadedModelDir: String? = null
        private set

    @Volatile
    var loadedBackend: MnnBackend? = null
        private set

    val isLoaded: Boolean get() = session?.isModelLoaded() == true

    /** The live session for the OpenAI-compatible server bridge; null when no model is loaded. */
    fun activeSession(): LlmSession? = session?.takeIf { it.isModelLoaded() }

    /**
     * Loads a model from [modelDir] (must contain config.json). [backend] overrides the
     * config's backend_type. Blocking native call — runs on IO.
     */
    suspend fun load(modelDir: String, backend: MnnBackend = MnnBackend.CPU) =
        withContext(Dispatchers.IO) {
            unloadInternal()
            val dir = File(modelDir)
            require(dir.isDirectory) { "Model dir not found: $modelDir" }
            val configPath = File(dir, "config.json").absolutePath
            require(File(configPath).exists()) { "config.json missing in $modelDir" }

            val modelId = "local/${dir.absolutePath}"
            val newSession = chatService.createLlmSession(
                modelId = modelId,
                modelDir = configPath,
                sessionIdParam = null,
                chatDataItemList = null,
                supportOmni = false,
                backendType = backend.configValue,
                useCustomConfig = true,
            )
            newSession.setKeepHistory(true)
            Log.d(TAG, "Loading model $modelId on ${backend.configValue}")
            newSession.load()
            session = newSession
            loadedModelDir = modelDir
            loadedBackend = backend
            Log.d(TAG, "Model loaded: $modelId")
        }

    /**
     * Streams generation for a single [prompt] turn. Each emission is an incremental chunk of
     * decoded text. Collecting on a cancellable scope stops generation (listener returns stop=true).
     */
    fun generate(prompt: String): Flow<GenerationChunk> =
        streamSubmit { session, listener -> session.generate(prompt, emptyMap(), listener) }

    /**
     * Streams a full multi-turn conversation as (role, content) pairs. The native side applies
     * the model's chat template (system prompt, roles) — used by the chat provider and server.
     */
    fun generateChat(messages: List<Pair<String, String>>): Flow<GenerationChunk> {
        // The session's submitFullHistory takes android.util.Pair (native-facing type).
        val androidHistory = messages.map { android.util.Pair(it.first, it.second) }
        return streamSubmit { session, listener -> session.submitFullHistory(androidHistory, listener) }
    }

    /** Shared streaming scaffold: runs a blocking native submit off-thread, bridges to a Flow. */
    private inline fun streamSubmit(
        crossinline submit: (LlmSession, GenerateProgressListener) -> Any?,
    ): Flow<GenerationChunk> = callbackFlow {
        val active = session ?: throw IllegalStateException("No model loaded")
        val worker = CoroutineScope(Dispatchers.IO).launch {
            val listener = object : GenerateProgressListener {
                // Contract (GPTee/MNN): return false = continue, true = stop.
                override fun onProgress(progress: String?): Boolean {
                    if (progress == null) return false
                    if (!isActive) return true // downstream cancelled
                    val ok = trySend(GenerationChunk(progress)).isSuccess
                    return !ok && !isActive
                }
            }
            try {
                val result = submit(active, listener)
                Log.d(TAG, "Generation done: $result")
                close()
            } catch (t: Throwable) {
                close(t)
            }
        }
        awaitClose { worker.cancel() }
    }.flowOn(Dispatchers.IO)

    /** Resets KV cache / multi-turn history without unloading the model. */
    suspend fun reset() = withContext(Dispatchers.IO) { session?.reset() }

    suspend fun unload() = withContext(Dispatchers.IO) { unloadInternal() }

    private fun unloadInternal() {
        session?.let {
            try {
                it.release()
            } catch (t: Throwable) {
                Log.w(TAG, "release failed", t)
            }
        }
        session = null
        loadedModelDir = null
        loadedBackend = null
    }

    private companion object {
        const val TAG = "OrionMnnEngine"
    }
}
