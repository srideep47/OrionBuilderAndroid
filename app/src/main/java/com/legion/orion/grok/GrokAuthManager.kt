package com.legion.orion.grok

import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Drives `grok login --device-auth` and reports auth state — the Grok counterpart of
 * [com.legion.orion.codex.CodexAuthManager].
 *
 * **Why device-code (not browser OAuth).** `grok login` defaults to opening a browser for OAuth at
 * `auth.x.ai`. That is a poor fit inside proot: no desktop session, no `xdg-open` target. Device-code
 * prints a URL + short code, polls until approved, and needs no inbound connection — the user can
 * approve on any other device. This is the same "simple login" shape Codex uses.
 *
 * Grok has no `login status` subcommand. Status is probed via `grok models` (prints
 * "You are logged in…") with a fallback to a non-empty `~/.grok/auth.json`.
 */
class GrokAuthManager(private val toolchain: GrokToolchain) {

    private val json = Json { ignoreUnknownKeys = true }

    data class AuthStatus(
        val loggedIn: Boolean,
        /** e.g. "Grok.com (OAuth)" or an email when present in auth.json. */
        val method: String?,
        val raw: String,
    )

    private val envPrefix =
        "export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
            "export HOME=/root; export GROK_HOME=/root/.grok; " +
            "(umask 077; mkdir -p \"\$GROK_HOME\"); export TERM=dumb; "

    private fun baseCommand(innerScript: String): List<String> = listOf(
        toolchain.base.prootBin.absolutePath,
        // No "-0" (fake root) — consistent with every other proot call site.
        "-r", toolchain.base.rootfsDir.absolutePath,
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        "-w", "/root",
        "/bin/bash", "-c", innerScript,
    )

    private fun ProcessBuilder.withProotEnv(): ProcessBuilder = apply {
        environment()["PROOT_LOADER"] = toolchain.base.loaderBin.absolutePath
        environment()["PROOT_LOADER_32"] = toolchain.base.loader32Bin.absolutePath
        environment()["PROOT_TMP_DIR"] = toolchain.base.prootTmpDir.absolutePath
        environment()["LD_LIBRARY_PATH"] = toolchain.base.libDir.absolutePath
    }

    suspend fun status(): AuthStatus = withContext(Dispatchers.IO) {
        val fromFile = parseAuthFile()
        // Prefer on-disk OAuth cache: a failed/slow `grok models` probe must not flash "Sign in"
        // for an already-authenticated SuperGrok account (common right after app restart).
        if (fromFile.loggedIn) {
            return@withContext AuthStatus(
                loggedIn = true,
                method = fromFile.email ?: "Grok.com (cached)",
                raw = fromFile.raw,
            )
        }
        val proc = ProcessBuilder(baseCommand("$envPrefix grok models 2>&1"))
            .redirectErrorStream(true)
            .withProotEnv()
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(45, TimeUnit.SECONDS)
        if (!finished) proc.destroyForcibly()

        val loggedInByProbe = finished &&
            proc.exitValue() == 0 &&
            output.contains("logged in", ignoreCase = true)
        AuthStatus(
            loggedIn = loggedInByProbe,
            method = if (loggedInByProbe) "Grok.com account" else null,
            raw = output,
        )
    }

    private data class FileAuth(val loggedIn: Boolean, val email: String?, val raw: String)

    private fun parseAuthFile(): FileAuth {
        val file = File(toolchain.grokHomeOnHost, "auth.json")
        if (!file.isFile) return FileAuth(false, null, "")
        val text = runCatching { file.readText() }.getOrDefault("")
        if (text.isBlank() || text.trim() == "{}") return FileAuth(false, null, text)
        val email = runCatching {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching null
            root.values.firstOrNull()?.let { entry ->
                (entry as? JsonObject)?.get("email")?.toString()?.trim('"')
            }
        }.getOrNull()
        return FileAuth(loggedIn = true, email = email, raw = text)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(baseCommand("$envPrefix grok logout 2>&1"))
            .redirectErrorStream(true)
            .withProotEnv()
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            error("Grok Build sign-out timed out")
        }
        // CLI owns auth.json layout; also clear host-side cache if logout left remnants.
        if (proc.exitValue() != 0) {
            val auth = File(toolchain.grokHomeOnHost, "auth.json")
            if (auth.isFile) auth.delete()
            if (auth.isFile) {
                error(output.lineSequence().lastOrNull { it.isNotBlank() }?.take(240) ?: "Grok sign-out failed")
            }
        }
    }

    /**
     * Starts `grok login --device-auth`, invoking [onPrompt] once a verification URL (and optional
     * user code) has been parsed from the process output.
     */
    suspend fun beginDeviceLogin(onPrompt: (DevicePrompt) -> Unit): LoginSession = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(baseCommand("$envPrefix grok login --device-auth 2>&1"))
            .redirectErrorStream(true)
            .withProotEnv()
            .start()
        val session = LoginSession(proc, proc.inputStream.bufferedReader())
        session.startCapturing(onPrompt)
        session
    }

    data class DevicePrompt(val url: String, val userCode: String?)

    class LoginSession internal constructor(
        private val process: Process,
        private val reader: BufferedReader,
    ) {
        private val outputSoFar = StringBuilder()
        @Volatile private var promptEmitted = false

        internal fun startCapturing(onPrompt: (DevicePrompt) -> Unit) {
            Thread({
                val buf = CharArray(4096)
                while (true) {
                    val n = runCatching { reader.read(buf) }.getOrDefault(-1)
                    if (n < 0) break
                    val accumulated = synchronized(outputSoFar) {
                        outputSoFar.append(buf, 0, n)
                        outputSoFar.toString()
                    }
                    if (!promptEmitted) {
                        // Scan the full buffer — URL/code can split across reads (Claude login bug).
                        val url = URL_REGEX.find(accumulated)?.value?.trimEnd('.', ',', ')', ']', '"')
                        if (url != null) {
                            promptEmitted = true
                            onPrompt(DevicePrompt(url, CODE_REGEX.find(accumulated)?.groupValues?.get(1)))
                        }
                    }
                }
            }, "grok-login-reader").apply { isDaemon = true }.start()
        }

        /** Generous timeout: human must open URL on another device and approve. */
        suspend fun awaitResult(timeoutMs: Long = 5 * 60_000): Boolean = withContext(Dispatchers.IO) {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext false
            }
            process.exitValue() == 0
        }

        fun output(): String = synchronized(outputSoFar) { outputSoFar.toString() }

        fun cancel() {
            runCatching { process.destroyForcibly() }
        }

        private companion object {
            val URL_REGEX = Regex("https://\\S+")
            val CODE_REGEX = Regex("\\b([A-Z0-9]{4,}-[A-Z0-9]{4,})\\b")
        }
    }
}
