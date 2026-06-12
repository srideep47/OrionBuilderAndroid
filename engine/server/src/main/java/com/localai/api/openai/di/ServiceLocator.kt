package com.localai.api.openai.di

import com.localai.api.openai.interfaces.ChatSessionProvider
import com.localai.api.openai.runtime.LlmRuntimeController

/**
 * Service locator for the OpenAI-compatible server module.
 *
 * Orion change: the GPTee defaults (DefaultLlmRuntimeController / ChatSessionProviderImpl) are
 * NOT bundled here — they created their own MNN sessions and would conflict with the app's single
 * InferenceController. The app injects Orion-side implementations at startup via
 * [setChatSessionProvider] and [setLlmRuntimeController]; calling a getter before injection is a
 * programming error.
 */
object ServiceLocator {

    private var _chatSessionProvider: ChatSessionProvider? = null
    private var _llmRuntimeController: LlmRuntimeController? = null

    fun getChatSessionProvider(): ChatSessionProvider =
        _chatSessionProvider
            ?: error("ChatSessionProvider not set — call ServiceLocator.setChatSessionProvider() at startup")

    fun setChatSessionProvider(provider: ChatSessionProvider) {
        _chatSessionProvider = provider
    }

    fun getLlmRuntimeController(): LlmRuntimeController =
        _llmRuntimeController
            ?: error("LlmRuntimeController not set — call ServiceLocator.setLlmRuntimeController() at startup")

    fun setLlmRuntimeController(controller: LlmRuntimeController) {
        _llmRuntimeController = controller
    }

    fun reset() {
        _chatSessionProvider = null
        _llmRuntimeController = null
    }
}
