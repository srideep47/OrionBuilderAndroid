package com.legion.orion.data.providers

import com.legion.orion.core.database.ProviderEntity
import com.legion.orion.engine.providers.AnthropicProvider
import com.legion.orion.engine.providers.ChatProvider
import com.legion.orion.engine.providers.GoogleProvider
import com.legion.orion.engine.providers.LocalMnnProvider
import com.legion.orion.engine.providers.OpenAiProvider
import com.legion.orion.engine.providers.withRecovery
import com.legion.orion.inference.InferenceController
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ResolveResult {
    data class Ready(val provider: ChatProvider) : ResolveResult
    data class MissingKey(val providerId: String) : ResolveResult
    data class Misconfigured(val reason: String) : ResolveResult
}

/**
 * Turns a stored [ProviderEntity] into a live [ChatProvider]. Cloud providers are wrapped with
 * stream recovery; the local provider bridges to the loaded MNN session via [InferenceController].
 */
@Singleton
class ProviderResolver @Inject constructor(
    private val repo: ProviderRepository,
    private val local: InferenceController,
) {
    suspend fun resolve(provider: ProviderEntity): ResolveResult {
        if (provider.type == ProviderType.MNN_LOCAL) {
            return ResolveResult.Ready(LocalMnnProvider(local))
        }
        val key = repo.getKey(provider.id)
        if (key.isNullOrBlank()) return ResolveResult.MissingKey(provider.id)

        val chat: ChatProvider = when (provider.type) {
            ProviderType.ANTHROPIC ->
                AnthropicProvider(apiKey = key, baseUrl = provider.baseUrl ?: "https://api.anthropic.com")
            ProviderType.OPENAI ->
                OpenAiProvider(apiKey = key, baseUrl = provider.baseUrl ?: "https://api.openai.com/v1")
            ProviderType.GOOGLE ->
                GoogleProvider(apiKey = key, baseUrl = provider.baseUrl ?: "https://generativelanguage.googleapis.com")
            ProviderType.OPENAI_COMPAT -> {
                val base = provider.baseUrl?.takeIf { it.isNotBlank() }
                    ?: return ResolveResult.Misconfigured("Set the base URL for ${provider.label}")
                OpenAiProvider(apiKey = key, baseUrl = base)
            }
            else -> return ResolveResult.Misconfigured("Unknown provider type ${provider.type}")
        }
        return ResolveResult.Ready(chat.withRecovery())
    }
}
