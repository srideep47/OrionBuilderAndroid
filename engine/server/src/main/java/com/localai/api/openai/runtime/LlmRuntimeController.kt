package com.localai.api.openai.runtime

import com.localai.chat.model.ChatDataItem
import com.localai.llm.LlmSession

data class EnsureSessionResult(
    val success: Boolean,
    val session: LlmSession? = null,
    val modelId: String? = null,
    val reason: String? = null
)

interface LlmRuntimeController {
    fun ensureSession(
        modelId: String,
        forceReload: Boolean = false,
        useAppConfig: Boolean = false,
        configPath: String? = null,
        sessionId: String? = null,
        historyList: List<ChatDataItem>? = null,
        deferLoad: Boolean = false
    ): EnsureSessionResult
    fun getActiveSession(): LlmSession?
    fun getActiveModelId(): String?
    fun getThinkingEnabled(): Boolean?
    fun setThinkingEnabled(enabled: Boolean): Boolean
    fun releaseSession()
}
