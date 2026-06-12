package com.legion.orion.inference

import com.legion.orion.engine.mnn.GenerationChunk
import com.legion.orion.engine.mnn.MnnBackend
import com.legion.orion.engine.mnn.OrionMnnEngine
import com.legion.orion.engine.providers.ChatMessage
import com.legion.orion.engine.providers.LocalChatEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide state of the local inference engine. */
data class EngineState(
    val status: Status = Status.IDLE,
    val modelDir: String? = null,
    val backend: MnnBackend? = null,
    val error: String? = null,
) {
    enum class Status { IDLE, LOADING, READY, GENERATING, ERROR }
}

/**
 * The single owner of the loaded MNN model for the whole process. The foreground
 * [InferenceService] keeps this alive; UI observes [state] and calls [generate].
 */
@Singleton
class InferenceController @Inject constructor() : LocalChatEngine {

    private val engine = OrionMnnEngine()
    private val mutex = Mutex()

    private val _state = MutableStateFlow(EngineState())
    val state = _state.asStateFlow()

    suspend fun load(modelDir: String, backend: MnnBackend) = mutex.withLock {
        _state.value = EngineState(EngineState.Status.LOADING, modelDir, backend)
        try {
            engine.load(modelDir, backend)
            _state.value = EngineState(EngineState.Status.READY, modelDir, backend)
        } catch (t: Throwable) {
            _state.value = EngineState(EngineState.Status.ERROR, modelDir, backend, t.message)
            throw t
        }
    }

    /** Streams a single-prompt generation. Marks state GENERATING for its duration. */
    fun generate(prompt: String): Flow<GenerationChunk> =
        engine.generate(prompt).withGeneratingState()

    /**
     * [LocalChatEngine] bridge for the chat provider: renders the conversation to (role, content)
     * pairs (system prepended once) and streams raw decoded text.
     */
    override fun generateChat(system: String?, messages: List<ChatMessage>): Flow<String> {
        val pairs = buildList {
            system?.takeIf { it.isNotBlank() }?.let { add("system" to it) }
            messages.filter { it.role != ChatMessage.Role.SYSTEM }
                .forEach { add(it.role.name.lowercase() to it.content) }
        }
        return engine.generateChat(pairs).withGeneratingState().map { it.text }
    }

    private fun Flow<GenerationChunk>.withGeneratingState(): Flow<GenerationChunk> =
        onStart { _state.value = _state.value.copy(status = EngineState.Status.GENERATING) }
            .onCompletion {
                if (_state.value.status == EngineState.Status.GENERATING) {
                    _state.value = _state.value.copy(status = EngineState.Status.READY)
                }
            }

    suspend fun reset() = mutex.withLock { engine.reset() }

    suspend fun unload() = mutex.withLock {
        engine.unload()
        _state.value = EngineState(EngineState.Status.IDLE)
    }

    val isLoaded: Boolean get() = engine.isLoaded

    /** Exposes the loaded MNN session to the OpenAI-compatible server bridge. */
    fun activeLlmSession() = engine.activeSession()
}
