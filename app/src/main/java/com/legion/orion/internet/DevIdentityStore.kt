package com.legion.orion.internet

import com.legion.orion.core.datastore.SecretStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * User-owned developer identities for future Internet Manager account assist (Phase 2/3).
 *
 * Design rules (intentional):
 * - Only **credentials the user explicitly stores** (their own emails/passwords/tokens).
 * - No automated bulk signup farms, CAPTCHA bypass, or credential stuffing.
 * - Signup/login automation (when built) will require **per-action user approval** for sensitive sites.
 * - Mailbox access is for **reading OTP/verification codes** from a mailbox the user configures
 *   (IMAP), not for creating disposable abuse inboxes.
 *
 * Phase 1 only persists identities the user adds via settings/API; agents can *list* labels but
 * never receive raw secrets unless a future approved flow injects them into a browser session.
 */
@Singleton
class DevIdentityStore @Inject constructor(
    private val secrets: SecretStore,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class DevIdentity(
        val id: String,
        val label: String,
        val email: String,
        /** Optional service hint e.g. "github", "google", "generic". */
        val service: String = "generic",
        val notes: String = "",
        /** True when a password is stored in SecretStore under [passwordKey]. */
        val hasPassword: Boolean = false,
        /** True when IMAP settings exist for OTP polling. */
        val hasMailbox: Boolean = false,
    )

    @Serializable
    private data class IdentityIndex(val identities: List<DevIdentity> = emptyList())

    suspend fun list(): List<DevIdentity> = loadIndex().identities

    suspend fun get(id: String): DevIdentity? = list().firstOrNull { it.id == id }

    suspend fun upsert(
        id: String,
        label: String,
        email: String,
        service: String = "generic",
        notes: String = "",
        password: String? = null,
    ): DevIdentity {
        val current = loadIndex().identities.toMutableList()
        val existing = current.indexOfFirst { it.id == id }
        if (password != null) {
            secrets.put(passwordKey(id), password)
        }
        val hasPassword = password != null || !secrets.get(passwordKey(id)).isNullOrBlank()
        val hasMailbox = !secrets.get(mailboxKey(id)).isNullOrBlank()
        val row = DevIdentity(
            id = id,
            label = label,
            email = email,
            service = service,
            notes = notes,
            hasPassword = hasPassword,
            hasMailbox = hasMailbox,
        )
        if (existing >= 0) current[existing] = row else current += row
        saveIndex(IdentityIndex(current))
        return row
    }

    suspend fun delete(id: String) {
        val next = loadIndex().identities.filterNot { it.id == id }
        saveIndex(IdentityIndex(next))
        secrets.put(passwordKey(id), "")
        secrets.put(mailboxKey(id), "")
    }

    /** Returns password only for internal approved browser-fill flows — not for agent tool output. */
    suspend fun passwordFor(id: String): String? = secrets.get(passwordKey(id))?.takeIf { it.isNotBlank() }

    fun formatPublicList(identities: List<DevIdentity>): String {
        if (identities.isEmpty()) {
            return "No developer identities configured. Add one in Settings → Internet Manager " +
                "(email + optional password/mailbox) before asking agents to log in to services."
        }
        return identities.joinToString("\n") { id ->
            "- ${id.label} (${id.id}): email=${id.email} service=${id.service} " +
                "password=${if (id.hasPassword) "stored" else "none"} " +
                "mailbox=${if (id.hasMailbox) "configured" else "none"}" +
                id.notes.takeIf { it.isNotBlank() }?.let { " notes=$it" }.orEmpty()
        }
    }

    private suspend fun loadIndex(): IdentityIndex {
        val raw = secrets.get(INDEX_KEY) ?: return IdentityIndex()
        if (raw.isBlank()) return IdentityIndex()
        return runCatching { json.decodeFromString(IdentityIndex.serializer(), raw) }
            .getOrDefault(IdentityIndex())
    }

    private suspend fun saveIndex(index: IdentityIndex) {
        secrets.put(INDEX_KEY, json.encodeToString(IdentityIndex.serializer(), index))
    }

    private fun passwordKey(id: String) = "dev_identity_password_$id"
    private fun mailboxKey(id: String) = "dev_identity_mailbox_$id"

    companion object {
        private const val INDEX_KEY = "dev_identity_index_v1"
    }
}
