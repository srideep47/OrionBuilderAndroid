package com.legion.orion.engine.providers

import com.legion.orion.engine.providers.internal.HermesPrompt
import com.legion.orion.engine.providers.internal.HermesToolCallParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The contract the app fulfils with the loaded MNN model. Kept here (not in engine:mnn) so the
 * providers module stays free of Android engine deps; the app wires the real implementation
 * over InferenceController / OrionMnnEngine.
 */
interface LocalChatEngine {
    /** Streams raw decoded text for the given conversation. System prompt passed separately. */
    fun generateChat(system: String?, messages: List<ChatMessage>): Flow<String>
}

/**
 * Local MNN-backed provider. PROMPT_FORMAT tool calling: tool schemas are injected as a Hermes
 * `<tools>` system block and the raw stream is parsed by [HermesToolCallParser], which also
 * separates `<think>` reasoning from visible text.
 */
class LocalMnnProvider(
    private val engine: LocalChatEngine,
) : ChatProvider {

    override val toolCallingMode = ToolCallingMode.PROMPT_FORMAT

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val system = if (request.tools.isNotEmpty()) {
            buildString {
                request.system?.let { append(it); append("\n\n") }
                append(HermesPrompt.toolsSystemBlock(request.tools))
            }
        } else {
            request.system
        }
        val messages = HermesPrompt.renderForLocal(request.messages)
        val parser = HermesToolCallParser()
        try {
            engine.generateChat(system, messages).collect { raw ->
                parser.push(raw).forEach { emit(it) }
            }
            parser.flush().forEach { emit(it) }
        } catch (t: Throwable) {
            emit(StreamEvent.Error(t.message ?: "local generation failed", retryable = false))
        }
    }
}
