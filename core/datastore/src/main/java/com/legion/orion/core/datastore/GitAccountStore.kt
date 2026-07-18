package com.legion.orion.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** One named HTTPS git credential profile. The PAT itself is NOT stored here — it lives in
 * [SecretStore] under `git_pat_account_<id>`, matching how every other secret in this app is kept
 * out of plain DataStore. SSH stays a single shared on-device keypair (unaffected by this class);
 * only HTTPS-PAT accounts are multi-profile for now. */
data class GitAccount(
    val id: String,
    val label: String,
    val host: String,
    val username: String?,
    val createdAt: Long,
)

private val Context.gitAccountsDataStore by preferencesDataStore(name = "orion_git_accounts")

/** A small named list of git credential profiles, deliberately a DataStore file rather than a new
 * Room entity — [com.legion.orion.core.database.OrionDatabase] uses
 * `fallbackToDestructiveMigration()`, so a schema bump wipes all local chat/project/session data;
 * not worth that risk for a settings list this small. Serialized as a single JSON-array preference
 * value using `org.json` (already on the platform, no new dependency needed) rather than one entry
 * per account, since DataStore Preferences has no native support for a list of objects. */
@Singleton
class GitAccountStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        val ACCOUNTS_JSON = stringPreferencesKey("accounts_json")
    }

    val accounts: Flow<List<GitAccount>> =
        context.gitAccountsDataStore.data.map { prefs -> decode(prefs[ACCOUNTS_JSON]) }

    suspend fun add(label: String, host: String, username: String?): GitAccount {
        val account = GitAccount(
            id = "git_${System.currentTimeMillis()}",
            label = label,
            host = host,
            username = username,
            createdAt = System.currentTimeMillis(),
        )
        context.gitAccountsDataStore.edit { prefs ->
            val current = decode(prefs[ACCOUNTS_JSON])
            prefs[ACCOUNTS_JSON] = encode(current + account)
        }
        return account
    }

    suspend fun remove(id: String) {
        context.gitAccountsDataStore.edit { prefs ->
            val current = decode(prefs[ACCOUNTS_JSON])
            prefs[ACCOUNTS_JSON] = encode(current.filterNot { it.id == id })
        }
    }

    suspend fun get(id: String): GitAccount? =
        decode(context.gitAccountsDataStore.data.first()[ACCOUNTS_JSON]).firstOrNull { it.id == id }

    private fun encode(list: List<GitAccount>): String {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("label", a.label)
                    put("host", a.host)
                    put("username", a.username ?: JSONObject.NULL)
                    put("createdAt", a.createdAt)
                },
            )
        }
        return arr.toString()
    }

    private fun decode(json: String?): List<GitAccount> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                GitAccount(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    host = o.getString("host"),
                    username = if (o.isNull("username")) null else o.optString("username").ifBlank { null },
                    createdAt = o.optLong("createdAt", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }
}
