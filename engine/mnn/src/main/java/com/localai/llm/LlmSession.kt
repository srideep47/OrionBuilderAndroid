// Ported from GPTee/LocalAI (originally MnnLlmChat, Alibaba Group, Apache-2.0).
// Orion changes: Timber -> android.util.Log, mockLatex debug path removed,
// native lib renamed to "orionmnn". JNI symbols unchanged (Java_com_localai_llm_LlmSession_*).
package com.localai.llm;

import android.util.Log
import com.localai.llm.ChatService.Companion.provide
import com.localai.chat.model.ChatDataItem
import com.localai.modelsettings.ModelConfig
import com.localai.model.ModelTypeUtils
import com.localai.modelsettings.ModelConfig.Companion.getExtraConfigFile
import com.google.gson.Gson
import java.io.File
import java.util.stream.Collectors
import kotlin.concurrent.Volatile
import android.util.Pair
import com.localai.utils.MmapUtils
import com.localai.modelsettings.Jinja
import com.localai.modelsettings.JinjaContext
import com.localai.modelsettings.ModelConfig.Companion.loadConfig
import com.localai.utils.FileSplitter
import com.localai.qnn.QnnModule

class LlmSession(
    private val modelId: String,
    override var sessionId: String,
    private val configPath: String,
    var savedHistory: List<ChatDataItem>?,
    var backendType: String? = null,
    private val useCustomConfig: Boolean = true,
) : ChatSession {
    override var supportOmni: Boolean = false
    private var nativePtr: Long = 0

    @Volatile
    private var modelLoading = false

    @Volatile
    private var generating = false

    @Volatile
    private var releaseRequested = false

    private var keepHistory = false

    private var isQnn = false

    override fun getHistory(): List<ChatDataItem>? {
        return savedHistory
    }

    override fun setHistory(history: List<ChatDataItem>?) {
    }

    override fun load() {
        Log.d(TAG, "MNN_DEBUG load begin modelId: $modelId backend: $backendType")
        modelLoading = true
        isQnn = ModelTypeUtils.isQnnModel(modelId)

        checkAndMergeSplitFiles()
        var historyStringList: List<String>? = null
        val currentHistory = this.savedHistory
        if (!currentHistory.isNullOrEmpty()) {
            historyStringList =
                currentHistory.stream()
                    .map { obj: ChatDataItem -> obj.text }
                    .filter { obj: String? -> obj != null }
                    .map { obj: String? -> obj!! }
                    .collect(Collectors.toList())
        }
        val config = if (useCustomConfig) {
            ModelConfig.loadMergedConfig(configPath, getExtraConfigFile(modelId))!!
        } else {
            ModelConfig.loadDefaultConfig(configPath)!!
        }
        var rootCacheDir: String? = ""
        if (config.useMmap == true) {
            rootCacheDir = MmapUtils.getMmapDir(modelId)
            File(rootCacheDir).mkdirs()
        }
        val configMap = HashMap<String, Any>().apply {
            put("is_r1", ModelTypeUtils.isR1Model(modelId))
            put("mmap_dir", rootCacheDir ?: "")
            put("keep_history", keepHistory)
        }
        val llmConfig = if (useCustomConfig) {
            ModelConfig.loadMergedConfig(configPath, getExtraConfigFile(modelId))!!
        } else {
            ModelConfig.loadDefaultConfig(configPath)!!
        }
        // Override backend type from constructor only if not null
        if (backendType != null) {
            llmConfig.backendType = backendType
        }
        if (isQnn) {
            llmConfig.visualModel = "visual_qnn_${QnnModule.modelMiddleName()}.mnn"
        }
        Log.d(TAG, "MNN_DEBUG load initNative")
        nativePtr = initNative(
            configPath,
            historyStringList,
            Gson().toJson(llmConfig),
            Gson().toJson(configMap),
        )
        Log.d(TAG, "MNN_DEBUG load initNative end")
        modelLoading = false
        if (nativePtr == 0L) {
            Log.e(TAG, "Model load failed - native initialization returned null pointer")
            throw IllegalStateException("Model load failed - the model module could not be loaded")
        }
        if (releaseRequested) {
            release()
        }
    }

    /** Check if the model is successfully loaded and ready for inference */
    fun isModelLoaded(): Boolean {
        return nativePtr != 0L
    }

    /** Check and merge split files for the current model */
    private fun checkAndMergeSplitFiles() {
        try {
            val configFile = File(configPath)
            val modelDir = configFile.parentFile

            if (modelDir != null && modelDir.exists()) {
                if (FileSplitter.needsMerging(modelDir)) {
                    Log.d(TAG, "Found split files that need merging in ${modelDir.absolutePath}")
                    val success = FileSplitter.mergeAllSplitFiles(modelDir)
                    if (!success) {
                        Log.w(TAG, "Failed to merge some split files for model: $modelId")
                    }
                }
            } else {
                Log.w(TAG, "Model directory not found: ${modelDir?.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking/merging split files for model: $modelId", e)
        }
    }

    fun getConfig(): ModelConfig? {
        return ModelConfig.loadMergedConfig(configPath, getExtraConfigFile(modelId))
    }

    private fun generateNewSessionId(): String {
        this.sessionId = System.currentTimeMillis().toString()
        return this.sessionId
    }

    override fun generate(
        prompt: String,
        params: Map<String, Any>,
        progressListener: GenerateProgressListener,
    ): HashMap<String, Any> {
        synchronized(this) {
            Log.d(TAG, "MNN_DEBUG submit prompt (len=${prompt.length})")
            generating = true
            val result = submitNative(nativePtr, prompt, keepHistory, progressListener)
            generating = false
            if (releaseRequested) {
                release()
            }
            return result
        }
    }

    override fun reset(): String {
        synchronized(this) {
            resetNative(nativePtr)
        }
        return generateNewSessionId()
    }

    override fun release() {
        synchronized(this) {
            Log.d(TAG, "MNN_DEBUG release nativePtr: $nativePtr mGenerating: $generating")
            if (!generating && !modelLoading) {
                releaseInner()
            } else {
                releaseRequested = true
                while (generating || modelLoading) {
                    try {
                        (this as Object).wait()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        Log.e(TAG, "Thread interrupted while waiting for release", e)
                    }
                }
                releaseInner()
            }
        }
    }

    private fun releaseInner() {
        if (nativePtr != 0L) {
            releaseNative(nativePtr)
            nativePtr = 0
            provide().removeSession(sessionId)
            (this as Object).notifyAll()
        }
    }

    private external fun initNative(
        configPath: String?,
        history: List<String>?,
        mergedConfigStr: String?,
        configJsonStr: String?,
    ): Long

    private external fun submitNative(
        instanceId: Long,
        input: String,
        keepHistory: Boolean,
        listener: GenerateProgressListener,
    ): HashMap<String, Any>

    private external fun resetNative(instanceId: Long)

    private external fun getDebugInfoNative(instanceId: Long): String

    private external fun releaseNative(instanceId: Long)

    private external fun setWavformCallbackNative(
        instanceId: Long,
        listener: AudioDataListener?,
    ): Boolean

    override fun setKeepHistory(keepHistory: Boolean) {
        this.keepHistory = keepHistory
    }

    override fun setEnableAudioOutput(enable: Boolean) {
        updateEnableAudioOutputNative(nativePtr, enable)
    }

    override val debugInfo
        get() = getDebugInfoNative(nativePtr) + "\n"

    fun setAudioDataListener(listener: AudioDataListener?) {
        synchronized(this) {
            if (nativePtr != 0L) {
                setWavformCallbackNative(nativePtr, listener)
            } else {
                Log.e(TAG, "nativePtr null")
            }
        }
    }

    fun updateMaxNewTokens(maxNewTokens: Int) {
        updateMaxNewTokensNative(nativePtr, maxNewTokens)
    }

    fun updateSystemPrompt(systemPrompt: String) {
        updateSystemPromptNative(nativePtr, systemPrompt)
    }

    override fun updateThinking(thinking: Boolean) {
        val loadedConfig = loadConfig(modelId)
        loadedConfig?.let {
            loadedConfig.jinja = Jinja(context = JinjaContext(enableThinking = thinking))
            ModelConfig.saveConfig(getExtraConfigFile(modelId), loadedConfig)
            updateConfig(Gson().toJson(loadedConfig))
        }
    }

    fun updateConfig(configJson: String) {
        Log.d(TAG, "updateConfig: $configJson")
        updateConfigNative(nativePtr, configJson)
    }

    private external fun updateEnableAudioOutputNative(llmPtr: Long, enable: Boolean)

    private external fun updateMaxNewTokensNative(llmPtr: Long, maxNewTokens: Int)

    private external fun updateSystemPromptNative(llmPtr: Long, systemPrompt: String)

    private external fun updateAssistantPromptNative(llmPtr: Long, assistantPrompt: String)

    private external fun updateConfigNative(llmPtr: Long, configJson: String)

    companion object {
        const val TAG: String = "LlmSession"

        init {
            System.loadLibrary("orionmnn")
        }
    }

    /**
     * Submit a full role/content history (used by the OpenAI-compatible server + agent loop).
     * Takes android.util.Pair to match the native-facing type the server already builds.
     */
    fun submitFullHistory(
        history: List<Pair<String, String>>,
        progressListener: GenerateProgressListener,
    ): HashMap<String, Any> {
        synchronized(this) {
            Log.d(TAG, "MNN_DEBUG submitFullHistory with ${history.size} messages")
            val result = submitFullHistoryNative(nativePtr, history, progressListener)
            generating = false
            return result
        }
    }

    private external fun submitFullHistoryNative(
        nativePtr: Long,
        history: List<Pair<String, String>>,
        progressListener: GenerateProgressListener,
    ): HashMap<String, Any>

    fun modelId(): String {
        return modelId
    }

    fun getSystemPrompt(): String? {
        return getSystemPromptNative(nativePtr)
    }

    private external fun getSystemPromptNative(llmPtr: Long): String?

    private external fun dumpConfigNative(llmPtr: Long): String

    fun dumpConfig(): String {
        return if (nativePtr != 0L) {
            dumpConfigNative(nativePtr)
        } else {
            "{}"
        }
    }

    // Official benchmark functionality following llm_bench.cpp approach
    fun runBenchmark(
        commandParams: com.localai.benchmark.CommandParameters,
        testInstance: com.localai.benchmark.TestInstance,
        callback: com.localai.benchmark.BenchmarkCallback,
    ): com.localai.benchmark.BenchmarkResult {
        return try {
            runBenchmarkNative(
                nativePtr,
                commandParams.backend,
                commandParams.threads,
                commandParams.useMmap,
                commandParams.power,
                commandParams.precision,
                commandParams.memory,
                commandParams.dynamicOption,
                commandParams.nPrompt,
                commandParams.nGenerate,
                commandParams.nRepeat,
                commandParams.kvCache == "true",
                testInstance,
                callback,
            )
        } catch (e: Exception) {
            com.localai.benchmark.BenchmarkResult(
                testInstance = testInstance,
                success = false,
                errorMessage = "benchmark failed: ${e.message}",
            )
        }
    }

    private external fun runBenchmarkNative(
        nativePtr: Long,
        backend: Int,
        threads: Int,
        useMmap: Boolean,
        power: Int,
        precision: Int,
        memory: Int,
        dynamicOption: Int,
        nPrompt: Int,
        nGenerate: Int,
        nRepeat: Int,
        kvCache: Boolean,
        testInstance: com.localai.benchmark.TestInstance,
        callback: com.localai.benchmark.BenchmarkCallback,
    ): com.localai.benchmark.BenchmarkResult
}
