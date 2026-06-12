// Ported from GPTee/LocalAI (originally MnnLlmChat, Alibaba Group, Apache-2.0).
// Orion changes: ModelDownloadManager branch removed from getDefaultConfigFile (the
// marketplace/downloader returns in a later phase); models load via "local/<abs-path>" ids.
package com.localai.modelsettings

import android.util.Log
import com.alibaba.mls.api.ApplicationProvider
import com.localai.model.ModelUtils
import com.localai.utils.FileUtils
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import com.google.gson.annotations.SerializedName

data class JinjaContext(
    @SerializedName("enable_thinking") var enableThinking: Boolean = false,
)

data class Jinja(
    @SerializedName("context") var context: JinjaContext? = null,
)

data class ModelConfig(
    @SerializedName("llm_model") var llmModel: String?,
    @SerializedName("llm_weight") var llmWeight: String?,
    @SerializedName("backend_type") var backendType: String?,
    @SerializedName("thread_num") var threadNum: Int?,
    @SerializedName("precision") var precision: String?,
    @SerializedName("use_mmap") var useMmap: Boolean?,
    @SerializedName("memory") var memory: String?,
    @SerializedName("system_prompt") var systemPrompt: String?,
    @SerializedName("sampler_type") var samplerType: String?,
    @SerializedName("mixed_samplers") var mixedSamplers: MutableList<String>?,
    @SerializedName("temperature") var temperature: Float?,
    @SerializedName("topP") var topP: Float?,
    @SerializedName("topK") var topK: Int?,
    @SerializedName("minP") var minP: Float?,
    var tfsZ: Float?,
    var typical: Float?,
    var penalty: Float?,
    @SerializedName("n_gram") var nGram: Int?,
    @SerializedName("ngram_factor") var nGramFactor: Float?,
    @SerializedName("max_new_tokens") var maxNewTokens: Int?,
    @SerializedName("assistant_prompt_template") var assistantPromptTemplate: String?,
    @SerializedName("penalty_sampler") var penaltySampler: String?,
    @SerializedName("jinja") var jinja: Jinja?,
    @SerializedName("visual_model") var visualModel: String?,
) {

    fun samplerEquals(loadedConfig: ModelConfig): Boolean {
        return this.samplerType == loadedConfig.samplerType &&
            this.mixedSamplers == loadedConfig.mixedSamplers &&
            this.temperature == loadedConfig.temperature &&
            this.topP == loadedConfig.topP &&
            this.topK == loadedConfig.topK &&
            this.minP == loadedConfig.minP &&
            this.tfsZ == loadedConfig.tfsZ &&
            this.typical == loadedConfig.typical &&
            this.penalty == loadedConfig.penalty &&
            this.nGram == loadedConfig.nGram &&
            this.nGramFactor == loadedConfig.nGramFactor &&
            this.penaltySampler == loadedConfig.penaltySampler &&
            this.visualModel == loadedConfig.visualModel
    }

    companion object {

        const val TAG = "ModelConfig"

        fun loadDefaultConfig(filePath: String): ModelConfig? {
            return try {
                val file = File(filePath)
                val json = file.readText()
                Gson().fromJson(json, ModelConfig::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        fun loadConfig(modelId: String): ModelConfig? {
            val defaultConfigFile = getDefaultConfigFile(modelId)
            if (defaultConfigFile.isNullOrEmpty()) {
                Log.w(TAG, "loadConfig: default config not found for modelId=$modelId")
                return null
            }
            return loadMergedConfig(defaultConfigFile, getExtraConfigFile(modelId))
        }

        fun loadMergedConfig(originalFilePath: String, overrideFilePath: String): ModelConfig? {
            return try {
                val originalFile = File(originalFilePath)
                val originalJson = JsonParser.parseString(originalFile.readText()).asJsonObject

                val overrideFile = File(overrideFilePath)
                if (overrideFile.exists()) {
                    val overrideJson = JsonParser.parseString(overrideFile.readText()).asJsonObject
                    mergeJson(originalJson, overrideJson)
                }
                Gson().fromJson(originalJson, ModelConfig::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        fun getDefaultConfigFile(modelId: String): String? {
            if (modelId.startsWith("local/")) {
                val localPath = modelId.removePrefix("local/")
                val configFilePath = File(localPath, "config.json")
                if (configFilePath.exists()) {
                    return configFilePath.absolutePath
                }
                return null
            }
            // Downloaded-model resolution returns with the marketplace phase.
            return null
        }

        /** Keys that must not be overwritten by empty string - model paths and backend from config.json */
        private val PROTECTED_KEYS = setOf("llm_model", "llm_weight", "backend_type")

        private fun mergeJson(original: JsonObject, override: JsonObject) {
            for (key in override.keySet()) {
                val overrideVal = override.get(key)
                // Don't let empty string overwrite model paths - custom_config may have saved
                // defaultConfig's empty llm_model/llm_weight, which would break model load
                if (key in PROTECTED_KEYS && overrideVal.isJsonPrimitive &&
                    overrideVal.asJsonPrimitive.isString && overrideVal.asString.isBlank() &&
                    original.has(key) && original.get(key).isJsonPrimitive &&
                    original.get(key).asJsonPrimitive.isString && original.get(key).asString.isNotBlank()
                ) {
                    continue
                }
                original.add(key, overrideVal)
            }
        }

        fun saveConfig(filePath: String, config: ModelConfig): Boolean {
            return try {
                val file = File(filePath)
                FileUtils.ensureParentDirectoriesExist(file)
                val gson = GsonBuilder()
                    .setPrettyPrinting()
                    .disableHtmlEscaping()
                    .create()
                val jsonString = gson.toJson(config)
                file.writeText(jsonString)
                true
            } catch (e: Exception) {
                Log.e(TAG, "saveConfig error", e)
                false
            }
        }

        fun getExtraConfigFile(modelId: String): String {
            return getModelConfigDir(modelId) + "/custom_config.json"
        }

        /** Delete custom_config.json so next load uses base config.json only (restores defaults). */
        fun deleteExtraConfig(modelId: String): Boolean {
            return try {
                val file = File(getExtraConfigFile(modelId))
                file.exists() && file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "deleteExtraConfig error", e)
                false
            }
        }

        fun getModelConfigDir(modelId: String): String {
            return ApplicationProvider.get().filesDir.toString() + "/configs/" +
                ModelUtils.safeModelId(modelId)
        }

        val defaultConfig: ModelConfig = ModelConfig(
            llmModel = null,
            llmWeight = null,
            backendType = null,
            threadNum = 4,
            precision = "low",
            memory = "",
            systemPrompt = "You are a helpful assistant.",
            samplerType = "",
            mixedSamplers = mutableListOf("topK", "topP", "minP", "temperature"),
            temperature = 0.6f,
            topP = 0.95f,
            topK = 20,
            minP = 0.05f,
            tfsZ = 1.0f,
            typical = 0.95f,
            penalty = 1.02f,
            nGram = 8,
            nGramFactor = 1.02f,
            maxNewTokens = 2048,
            assistantPromptTemplate = "",
            penaltySampler = "greedy",
            useMmap = false,
            jinja = null,
            visualModel = "visual.mnn",
        )
    }
}
