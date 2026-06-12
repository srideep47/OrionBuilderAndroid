package com.legion.orion.data.publish

import com.legion.orion.core.common.Dispatcher
import com.legion.orion.core.common.OrionDispatchers
import com.legion.orion.core.datastore.SecretStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube OAuth (installed-app / authorization-code + refresh flow). Tokens and
 * the user's OAuth client credentials live in the Keystore-backed [SecretStore]
 * (never plaintext). The user supplies their own Google OAuth client id/secret
 * (only they can create one for their project) via Settings; everything else —
 * consent URL, code exchange, silent refresh — is handled here.
 */
@Singleton
class YouTubeAuth @Inject constructor(
    private val secrets: SecretStore,
    private val http: OkHttpClient,
    @Dispatcher(OrionDispatchers.IO) private val io: CoroutineDispatcher,
) {
    companion object {
        const val REDIRECT_URI = "com.legion.orion:/oauth2redirect"
        const val SCOPE = "https://www.googleapis.com/auth/youtube.upload"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val K_CLIENT_ID = "yt_client_id"
        private const val K_CLIENT_SECRET = "yt_client_secret"
        private const val K_ACCESS = "yt_access_token"
        private const val K_REFRESH = "yt_refresh_token"
        private const val K_EXPIRY = "yt_expiry_ms"
    }

    suspend fun isConfigured(): Boolean = !secrets.get(K_CLIENT_ID).isNullOrBlank()
    suspend fun isConnected(): Boolean = !secrets.get(K_REFRESH).isNullOrBlank()

    suspend fun setClient(clientId: String, clientSecret: String) {
        secrets.put(K_CLIENT_ID, clientId.trim())
        secrets.put(K_CLIENT_SECRET, clientSecret.trim())
    }

    suspend fun consentUrl(): String? {
        val clientId = secrets.get(K_CLIENT_ID) ?: return null
        return "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=${enc(clientId)}" +
            "&redirect_uri=${enc(REDIRECT_URI)}" +
            "&response_type=code" +
            "&scope=${enc(SCOPE)}" +
            "&access_type=offline&prompt=consent"
    }

    /** Exchange an authorization code for tokens (after the consent redirect). */
    suspend fun exchangeCode(code: String): Result<Unit> = withContext(io) {
        runCatching {
            val clientId = secrets.get(K_CLIENT_ID) ?: error("No client id configured")
            val clientSecret = secrets.get(K_CLIENT_SECRET).orEmpty()
            val form = FormBody.Builder()
                .add("code", code)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("redirect_uri", REDIRECT_URI)
                .add("grant_type", "authorization_code")
                .build()
            postToken(form)
        }
    }

    /** A valid access token, refreshing via the refresh token when expired. */
    suspend fun accessToken(): String? = withContext(io) {
        val expiry = secrets.get(K_EXPIRY)?.toLongOrNull() ?: 0
        val access = secrets.get(K_ACCESS)
        if (!access.isNullOrBlank() && System.currentTimeMillis() < expiry - 60_000) return@withContext access
        refresh()
    }

    private suspend fun refresh(): String? {
        val clientId = secrets.get(K_CLIENT_ID) ?: return null
        val clientSecret = secrets.get(K_CLIENT_SECRET).orEmpty()
        val refresh = secrets.get(K_REFRESH) ?: return null
        return runCatching {
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("refresh_token", refresh)
                .add("grant_type", "refresh_token")
                .build()
            postToken(form)
            secrets.get(K_ACCESS)
        }.getOrNull()
    }

    suspend fun disconnect() {
        secrets.remove(K_ACCESS); secrets.remove(K_REFRESH); secrets.remove(K_EXPIRY)
    }

    private suspend fun postToken(form: FormBody) {
        val req = Request.Builder().url(TOKEN_URL).post(form).build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            require(r.isSuccessful) { "token HTTP ${r.code}: $body" }
            field(body, "access_token")?.let { secrets.put(K_ACCESS, it) }
            field(body, "refresh_token")?.let { secrets.put(K_REFRESH, it) }
            val expiresIn = field(body, "expires_in")?.toLongOrNull() ?: 3600
            secrets.put(K_EXPIRY, (System.currentTimeMillis() + expiresIn * 1000).toString())
        }
    }

    private fun field(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"?([^\",}]+)\"?").find(json)?.groupValues?.get(1)?.trim()

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
