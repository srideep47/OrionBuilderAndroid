package com.legion.orion.marta

import com.legion.orion.engine.mnn.MnnBackend
import com.legion.orion.engine.mnn.MnnSettings

/**
 * Snapdragon 8 Elite profile for Qwen3.5-2B-MNN (vision + tools).
 * CPU won our 4B bake-off on this SoC (10.8 vs 2.3 tok/s decode). The 2B pack
 * is always-on Marta: thinking off, short replies, mmap + int8 QKV, 6 perf cores.
 */
object MartaProfile {
    const val MODEL_DIR_NAME = "Qwen3.5-2B-MNN"
    const val DISPLAY_NAME = "Marta"
    val backend: MnnBackend = MnnBackend.CPU

    /** Spoken replies stay short so TTS starts quickly. */
    const val VOICE_MAX_NEW = 192
    const val TEXT_MAX_NEW = 384
    const val CONTEXT_TOKENS = 6144
    const val AGENT_ITERS = 6

    fun settings(voice: Boolean = false) = MnnSettings(
        threadNum = 6,
        precision = "low",
        memory = "low",
        power = "high",
        useMmap = true,
        useCachedMmap = true,
        kvCacheMmap = false,
        reuseKv = true,
        attentionMode = 10,
        dynamicOption = 0,
        maxAllTokens = CONTEXT_TOKENS,
        maxNewTokens = if (voice) VOICE_MAX_NEW else TEXT_MAX_NEW,
        temperature = 0.7f,
        topP = 0.9f,
        topK = 20,
        minP = 0.05f,
        samplerType = "mixed",
        enableThinking = false,
    )
}
