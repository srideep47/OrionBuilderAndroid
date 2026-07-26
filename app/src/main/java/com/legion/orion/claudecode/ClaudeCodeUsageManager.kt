package com.legion.orion.claudecode

import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

data class ClaudeQuotaWindow(
    val usedPercentage: Double? = null,
    val resetsAtEpochSeconds: Long? = null,
)

data class ClaudeModelQuota(
    val displayName: String,
    val usedPercentage: Double? = null,
    val resetsAtEpochSeconds: Long? = null,
)

data class ClaudeAccountUsage(
    val fiveHour: ClaudeQuotaWindow,
    val sevenDay: ClaudeQuotaWindow,
    val modelScoped: List<ClaudeModelQuota>,
    val fetchedAtMillis: Long = System.currentTimeMillis(),
)

/**
 * Reads the same account quota document used by Claude Code's `/usage` surface. The OAuth access
 * token never crosses the proot boundary or appears in a process argument: a tiny local Python
 * helper reads Claude Code's own credential file and prints only the quota response.
 *
 * Claude's official status-line payload is still the preferred live source. This manager fills the
 * print/headless-mode gap and supplies model-scoped weekly windows (currently Fable), which are not
 * part of the public status-line schema.
 */
class ClaudeCodeUsageManager(private val toolchain: ClaudeCodeToolchain) {
    suspend fun fetch(): ClaudeAccountUsage = withContext(Dispatchers.IO) {
        check(toolchain.status().provisioned) { "Claude Code runtime is not installed" }
        val helper = File(toolchain.rootfsDir, "root/.local/bin/orion-claude-usage.py")
        helper.parentFile?.mkdirs()
        if (!helper.exists() || helper.readText() != USAGE_HELPER) {
            helper.writeText(USAGE_HELPER)
            helper.setReadable(true, true)
        }

        val process = ProcessBuilder(
            toolchain.prootBin.absolutePath,
            "-r", toolchain.rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/usr/bin/python3", "/root/.local/bin/orion-claude-usage.py",
        ).redirectErrorStream(true).apply {
            environment()["HOME"] = "/root"
            environment()["SSL_CERT_FILE"] = "/etc/ssl/certs/ca-certificates.crt"
            environment()["PROOT_LOADER"] = toolchain.loaderBin.absolutePath
            environment()["PROOT_LOADER_32"] = toolchain.loader32Bin.absolutePath
            environment()["PROOT_TMP_DIR"] = toolchain.prootTmpDir.absolutePath
            environment()["LD_LIBRARY_PATH"] = toolchain.libDir.absolutePath
        }.start()

        if (!process.waitFor(25, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Claude usage refresh timed out")
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.exitValue() == 0) {
            output.lineSequence().lastOrNull()?.take(240) ?: "Claude usage refresh failed"
        }
        parseUsageResponse(output)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        internal fun parseUsageResponse(source: String): ClaudeAccountUsage {
            val root = json.parseToJsonElement(source) as JsonObject
            val limits = root["limits"] as? JsonArray

            fun limit(kind: String): JsonObject? = limits
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.string("kind") == kind }

            val fiveHour = limit("session")?.toWindowFromLimit()
                ?: (root["five_hour"] as? JsonObject)?.toWindow()
                ?: ClaudeQuotaWindow()
            val sevenDay = limit("weekly_all")?.toWindowFromLimit()
                ?: (root["seven_day"] as? JsonObject)?.toWindow()
                ?: ClaudeQuotaWindow()

            val scoped = buildList {
                limits?.mapNotNull { it as? JsonObject }
                    ?.filter { it.string("kind") == "weekly_scoped" }
                    ?.forEach { item ->
                        val scope = item["scope"] as? JsonObject
                        val model = scope?.get("model") as? JsonObject
                        val name = model?.string("display_name") ?: return@forEach
                        add(
                            ClaudeModelQuota(
                                displayName = name,
                                usedPercentage = percent(item.number("percent")),
                                resetsAtEpochSeconds = item.epochSeconds("resets_at"),
                            ),
                        )
                    }

                // Compatibility with older/newer response projections that expose named windows.
                root.entries
                    .filter { (key, value) -> key.startsWith("seven_day_") && value is JsonObject }
                    .forEach { (key, value) ->
                        val name = key.removePrefix("seven_day_").replace('_', ' ')
                            .split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
                        if (none { it.displayName.equals(name, ignoreCase = true) }) {
                            val window = (value as JsonObject).toWindow()
                            add(ClaudeModelQuota(name, window.usedPercentage, window.resetsAtEpochSeconds))
                        }
                    }
            }.ifEmpty { listOf(ClaudeModelQuota("Fable")) }

            return ClaudeAccountUsage(fiveHour, sevenDay, scoped)
        }

        private fun JsonObject.toWindow() = ClaudeQuotaWindow(
            usedPercentage = percent(number("utilization") ?: number("used_percentage")),
            resetsAtEpochSeconds = epochSeconds("resets_at"),
        )

        private fun JsonObject.toWindowFromLimit() = ClaudeQuotaWindow(
            usedPercentage = percent(number("percent") ?: number("utilization")),
            resetsAtEpochSeconds = epochSeconds("resets_at"),
        )

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.number(key: String): Double? =
            (this[key] as? JsonPrimitive)?.doubleOrNull

        /** The usage endpoint has returned both 0..1 utilization and 0..100 percent values. */
        private fun percent(value: Double?): Double? = value?.let { if (it in 0.0..1.0) it * 100.0 else it }

        private fun JsonObject.epochSeconds(key: String): Long? {
            val primitive = this[key] as? JsonPrimitive ?: return null
            primitive.content.toLongOrNull()?.let { return it }
            return runCatching { Instant.parse(primitive.content).epochSecond }.getOrNull()
        }

        private const val USAGE_HELPER = """import json
import urllib.request

def find_access_token(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key in ("accessToken", "access_token") and isinstance(child, str):
                return child
        for child in value.values():
            token = find_access_token(child)
            if token:
                return token
    elif isinstance(value, list):
        for child in value:
            token = find_access_token(child)
            if token:
                return token
    return None

with open("/root/.claude/.credentials.json", "r", encoding="utf-8") as handle:
    token = find_access_token(json.load(handle))
if not token:
    raise SystemExit("Claude OAuth credentials are unavailable")

request = urllib.request.Request(
    "https://api.anthropic.com/api/oauth/usage",
    headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/json",
        "Content-Type": "application/json",
        "anthropic-beta": "oauth-2025-04-20",
        "User-Agent": "claude-code/orion-mobile",
    },
)
with urllib.request.urlopen(request, timeout=20) as response:
    print(response.read().decode("utf-8"))
"""
    }
}
