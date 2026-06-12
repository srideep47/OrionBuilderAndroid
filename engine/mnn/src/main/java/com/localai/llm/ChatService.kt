// LLM-only port of GPTee's ChatService (diffusion/sana removed — Orion never generates
// image/video on-device; those jobs go to P2P peers).
package com.localai.llm

import android.text.TextUtils
import com.localai.chat.model.ChatDataItem

class ChatService {
    private val transformerSessionMap: MutableMap<String, ChatSession> = HashMap()

    @Synchronized
    fun createLlmSession(
        modelId: String?,
        modelDir: String?,
        sessionIdParam: String?,
        chatDataItemList: List<ChatDataItem>?,
        supportOmni: Boolean,
        backendType: String? = null,
        useCustomConfig: Boolean = true,
    ): LlmSession {
        val sessionId: String = if (TextUtils.isEmpty(sessionIdParam)) {
            System.currentTimeMillis().toString()
        } else {
            sessionIdParam!!
        }
        val session = LlmSession(modelId!!, sessionId, modelDir!!, chatDataItemList, backendType, useCustomConfig)
        session.supportOmni = supportOmni
        transformerSessionMap[sessionId] = session
        return session
    }

    @Synchronized
    fun getSession(sessionId: String): ChatSession? {
        return transformerSessionMap[sessionId]
    }

    @Synchronized
    fun removeSession(sessionId: String) {
        transformerSessionMap.remove(sessionId)
    }

    companion object {
        private var instance: ChatService? = null

        @JvmStatic
        @Synchronized
        fun provide(): ChatService {
            if (instance == null) {
                instance = ChatService()
            }
            return instance!!
        }
    }
}
