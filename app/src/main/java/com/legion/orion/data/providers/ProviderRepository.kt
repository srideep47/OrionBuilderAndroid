package com.legion.orion.data.providers

import com.legion.orion.core.database.ProviderDao
import com.legion.orion.core.database.ProviderEntity
import com.legion.orion.core.database.ProviderModelEntity
import com.legion.orion.core.datastore.SecretStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

object ProviderType {
    const val ANTHROPIC = "anthropic"
    const val OPENAI = "openai"
    const val GOOGLE = "google"
    const val OPENAI_COMPAT = "openai_compat"
    const val MNN_LOCAL = "mnn_local"
}

/**
 * Registry of model providers (cloud + on-device). API keys are stored only in [SecretStore]
 * (Keystore-AES); the providers table never holds secrets.
 */
@Singleton
class ProviderRepository @Inject constructor(
    private val providerDao: ProviderDao,
    private val secretStore: SecretStore,
) {
    fun observeProviders(): Flow<List<ProviderEntity>> = providerDao.observeAll()

    suspend fun modelsFor(providerId: String): List<ProviderModelEntity> = providerDao.modelsFor(providerId)

    suspend fun setKey(providerId: String, key: String) = secretStore.put(keyAlias(providerId), key)
    suspend fun getKey(providerId: String): String? = secretStore.get(keyAlias(providerId))
    suspend fun hasKey(providerId: String): Boolean = !getKey(providerId).isNullOrBlank()

    suspend fun setBaseUrl(provider: ProviderEntity, baseUrl: String) =
        providerDao.upsert(provider.copy(baseUrl = baseUrl))

    /** Seeds the built-in provider rows + a default model each, only if the table is empty. */
    suspend fun seedDefaultsIfEmpty() {
        if (providerDao.observeAll().first().isNotEmpty()) return
        seed(ProviderType.MNN_LOCAL, "On-device (MNN)", null, "local", 32_768, tools = true, vision = false)
        seed(ProviderType.ANTHROPIC, "Anthropic", null, "claude-sonnet-4-6", 200_000, tools = true, vision = true)
        seed(ProviderType.OPENAI, "OpenAI", null, "gpt-4o-mini", 128_000, tools = true, vision = true)
        seed(ProviderType.GOOGLE, "Google Gemini", null, "gemini-2.0-flash", 1_000_000, tools = true, vision = true)
        seed(ProviderType.OPENAI_COMPAT, "OpenAI-compatible", "", "", 32_768, tools = true, vision = false)
    }

    private suspend fun seed(
        type: String,
        label: String,
        baseUrl: String?,
        modelId: String,
        ctx: Int,
        tools: Boolean,
        vision: Boolean,
    ) {
        providerDao.upsert(ProviderEntity(id = type, type = type, baseUrl = baseUrl, label = label))
        if (modelId.isNotEmpty()) {
            providerDao.upsertModel(
                ProviderModelEntity(
                    id = "$type/$modelId",
                    providerId = type,
                    modelId = modelId,
                    contextWindow = ctx,
                    supportsTools = tools,
                    supportsVision = vision,
                ),
            )
        }
    }

    private fun keyAlias(providerId: String) = "provider_key_$providerId"
}
