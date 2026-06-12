package com.legion.orion.server

import com.localai.api.openai.interfaces.ChatSessionProvider
import com.localai.api.openai.runtime.EnsureSessionResult
import com.localai.api.openai.runtime.LlmRuntimeController
import com.localai.chat.model.ChatDataItem
import com.localai.llm.LlmSession
import com.legion.orion.inference.InferenceController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the ported OpenAI-compatible server to Orion's single model owner ([InferenceController]).
 *
 * The server NEVER creates or releases sessions — that would double memory against the app's
 * loaded model. It only reads the currently-loaded session; if nothing is loaded, chat requests
 * fail with NO_MODEL_LOADED until the user loads a model from the Engine screen.
 */
@Singleton
class OrionServerBridge @Inject constructor(
    private val controller: InferenceController,
) : ChatSessionProvider, LlmRuntimeController {

    // ── ChatSessionProvider ──
    override fun getLlmSession(): LlmSession? = controller.activeLlmSession()
    override fun hasActiveSession(): Boolean = controller.activeLlmSession() != null
    override fun getCurrentSessionId(): String? = controller.activeLlmSession()?.sessionId

    // ── LlmRuntimeController ──
    override fun ensureSession(
        modelId: String,
        forceReload: Boolean,
        useAppConfig: Boolean,
        configPath: String?,
        sessionId: String?,
        historyList: List<ChatDataItem>?,
        deferLoad: Boolean,
    ): EnsureSessionResult {
        val session = controller.activeLlmSession()
            ?: return EnsureSessionResult(success = false, modelId = modelId, reason = "NO_MODEL_LOADED")
        return EnsureSessionResult(success = true, session = session, modelId = session.modelId())
    }

    override fun getActiveSession(): LlmSession? = controller.activeLlmSession()

    override fun getActiveModelId(): String? = controller.activeLlmSession()?.modelId()

    override fun getThinkingEnabled(): Boolean? = null

    override fun setThinkingEnabled(enabled: Boolean): Boolean =
        controller.activeLlmSession()?.let {
            runCatching { it.updateThinking(enabled); true }.getOrDefault(false)
        } ?: false

    // The app owns the model lifecycle; stopping the server must not unload the user's model.
    override fun releaseSession() { /* no-op by design */ }
}
