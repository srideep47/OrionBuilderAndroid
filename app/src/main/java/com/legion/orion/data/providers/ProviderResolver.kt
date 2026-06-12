package com.legion.orion.data.providers

import com.legion.orion.core.database.ProviderEntity
import com.legion.orion.engine.providers.AnthropicProvider
import com.legion.orion.engine.providers.ChatProvider
import com.legion.orion.engine.providers.GoogleProvider
import com.legion.orion.engine.providers.LocalMnnProvider
import com.legion.orion.engine.providers.OpenAiProvider
import com.legion.orion.engine.providers.withRecovery
import com.legion.orion.inference.InferenceController
import com.legion.orion.infra.p2p.P2pController
import com.legion.orion.p2p.PeerChatProvider
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ResolveResult {
    data class Ready(val provider: ChatProvider) : ResolveResult
    data class MissingKey(val providerId: String) : ResolveResult
    data class Misconfigured(val reason: String) : ResolveResult
}

/**
 * Turns a stored [ProviderEntity] into a live [ChatProvider]. Cloud providers are wrapped with
 * stream recovery; the local provider bridges to the loaded MNN session via [InferenceController];
 * peer providers (synthesized, never stored) tunnel to a trusted peer's model over P2P.
 */
@Singleton
class ProviderResolver @Inject constructor(
    private val repo: ProviderRepository,
    private val local: InferenceController,
    private val p2p: P2pController,
) {
    suspend fun resolve(provider: ProviderEntity): ResolveResult {
        if (provider.type == ProviderType.MNN_LOCAL) {
            return ResolveResult.Ready(LocalMnnProvider(local))
        }
        if (provider.type == ProviderType.PEER) {
            val peerId = provider.id.removePrefix(PEER_ID_PREFIX)
            val peer = p2p.peers.value.firstOrNull { it.publicKey == peerId }
            if (peer == null || peer.status != "online") {
                return ResolveResult.Misconfigured("${provider.label} is not connected right now")
            }
            if (!peer.isTrusted) {
                return ResolveResult.Misconfigured("${provider.label} hasn't been approved as a friend")
            }
            return ResolveResult.Ready(PeerChatProvider(peerId, p2p))
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

    companion object {
        const val PEER_ID_PREFIX = "peer/"

        /** Synthesizes the (non-persisted) provider row for a trusted online peer. */
        fun peerProviderEntity(peer: com.legion.orion.infra.p2p.P2pPeer): ProviderEntity =
            ProviderEntity(
                id = PEER_ID_PREFIX + peer.publicKey,
                type = ProviderType.PEER,
                baseUrl = null,
                label = "${peer.displayName.ifBlank { peer.fingerprint }} (peer)",
            )
    }
}
