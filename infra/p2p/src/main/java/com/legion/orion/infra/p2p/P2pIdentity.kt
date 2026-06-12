package com.legion.orion.infra.p2p

import com.legion.orion.core.datastore.SecretStore
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stable P2P identity: a 32-byte ed25519 seed, generated once and persisted via [SecretStore]
 * (Android-Keystore-AES wrapped). The seed is injected into the bridge as hex so the same DHT
 * identity survives Node restarts; the bridge derives the full keypair from it with sodium.
 * Kotlin never needs to derive the public key itself — it reads `self` back from the bridge.
 */
@Singleton
class P2pIdentity @Inject constructor(
    private val secretStore: SecretStore,
) {
    /** Returns the persisted seed hex, generating + storing a fresh one on first use. */
    suspend fun seedHex(): String {
        secretStore.get(KEY)?.let { if (it.length == SEED_BYTES * 2) return it }
        val seed = ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) }
        val hex = seed.toHex()
        secretStore.put(KEY, hex)
        return hex
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (b in this@toHex) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4]); append(HEX[v and 0x0F])
        }
    }

    private companion object {
        const val KEY = "p2p_seed_ed25519"
        const val SEED_BYTES = 32
        const val HEX = "0123456789abcdef"
    }
}
