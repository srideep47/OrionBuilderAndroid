package com.legion.orion.data.git

import com.legion.orion.core.common.Dispatcher
import com.legion.orion.core.common.OrionDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GitHub OAuth "device flow" sign-in — the mobile-friendly alternative to pasting a personal
 * access token by hand. Unlike an authorization-code redirect flow, device flow never invokes a
 * callback URL at all: this requests a short-lived `user_code`, the caller shows it and opens
 * `verification_uri` in a browser, the user enters the code and approves on github.com, and this
 * polls in the background until GitHub reports the resulting access token (or the code expires/is
 * denied). No client secret is involved — GitHub's device flow treats the app as a public client,
 * so the client id (not a secret) is safe to bake into the app the way it's baked into any
 * distributed binary.
 */
@Singleton
class GitHubDeviceAuth @Inject constructor(
    private val http: OkHttpClient,
    @Dispatcher(OrionDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private companion object {
        const val CLIENT_ID = "Ov23liynni2lT5ZthhfA"
        const val SCOPE = "repo"
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    }

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSec: Int,
        val intervalSec: Int,
    )

    data class GitHubIdentity(val accessToken: String, val username: String?)

    /** Starts the flow: requests a device/user code pair, invokes [onCode] once with the user code
     * and verification URL to show, then polls for the token until the user approves (or the code
     * expires or is denied). */
    suspend fun beginLogin(onCode: (DeviceCode) -> Unit): Result<GitHubIdentity> = withContext(io) {
        runCatching {
            val code = requestDeviceCode()
            onCode(code)
            val token = pollForToken(code)
            GitHubIdentity(token, fetchUsername(token))
        }
    }

    private fun requestDeviceCode(): DeviceCode {
        val form = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPE)
            .build()
        val req = Request.Builder()
            .url(DEVICE_CODE_URL)
            .header("Accept", "application/json")
            .post(form)
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            require(r.isSuccessful) { "GitHub device-code request failed (HTTP ${r.code}): $body" }
            return DeviceCode(
                deviceCode = field(body, "device_code") ?: error("No device_code in response: $body"),
                userCode = field(body, "user_code") ?: error("No user_code in response: $body"),
                verificationUri = field(body, "verification_uri") ?: "https://github.com/login/device",
                expiresInSec = field(body, "expires_in")?.toIntOrNull() ?: 900,
                intervalSec = field(body, "interval")?.toIntOrNull() ?: 5,
            )
        }
    }

    private suspend fun pollForToken(code: DeviceCode): String {
        var intervalSec = code.intervalSec.coerceAtLeast(5)
        val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(intervalSec * 1000L)
            val form = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("device_code", code.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            val req = Request.Builder()
                .url(TOKEN_URL)
                .header("Accept", "application/json")
                .post(form)
                .build()
            // A transient network blip (DNS hiccup, brief connectivity loss while the user is off
            // in the browser typing the code) on any ONE poll must not kill the whole login — only
            // GitHub's own explicit terminal error fields (below) or the overall deadline should.
            val body = runCatching {
                http.newCall(req).execute().use { r -> r.body?.string().orEmpty() }
            }.getOrNull() ?: continue
            field(body, "access_token")?.let { return it }
            when (field(body, "error")) {
                "authorization_pending", null -> {} // not yet approved — keep polling
                "slow_down" -> intervalSec += 5
                "expired_token" -> error("Code expired — try signing in again")
                "access_denied" -> error("Sign-in was cancelled")
                else -> error(field(body, "error_description") ?: "GitHub sign-in failed")
            }
        }
        error("Sign-in timed out — try again")
    }

    private fun fetchUsername(token: String): String? {
        val req = Request.Builder()
            .url("https://api.github.com/user")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        return runCatching {
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (r.isSuccessful) field(body, "login") else null
            }
        }.getOrNull()
    }

    private fun field(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"?([^\",}]+)\"?").find(json)?.groupValues?.get(1)?.trim()
}
