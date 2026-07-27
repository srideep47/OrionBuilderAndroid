package com.legion.orion.codex

import java.io.BufferedReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drives `codex login --device-auth` and reports auth state, the Codex counterpart of
 * [com.legion.orion.claudecode.ClaudeCodeAuthManager].
 *
 * **Why the device-code flow and not the default browser flow.** `codex login` normally opens a browser
 * and waits for a redirect back to a short-lived `localhost` listener. That is a poor fit here even
 * though it would technically work (proot doesn't isolate the network, so an on-device browser can reach
 * a listener inside the chroot): the CLI would need to launch a browser from inside a chroot that has no
 * desktop session and no `xdg-open` target, and any hiccup leaves a dangling listener holding a port.
 * The device-code flow removes all of that — the CLI prints a URL and a short code, polls the token
 * endpoint itself, and needs no inbound connection at all. It also means the user can approve on their
 * PC instead of typing an OAuth password on a phone keyboard.
 *
 * This class only launches the process and surfaces what it prints. The actual sign-in is entirely the
 * user's own action in their own browser; nothing here ever sees or handles their password.
 */
class CodexAuthManager(private val toolchain: CodexToolchain) {

    data class AuthStatus(
        val loggedIn: Boolean,
        /** e.g. "ChatGPT" (subscription) vs "API key" — Codex reports the active mode, not an email. */
        val method: String?,
        val raw: String,
    )

    private val envPrefix =
        "export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
            "export HOME=/root; export CODEX_HOME=/root/.codex; " +
            "(umask 077; mkdir -p \"\$CODEX_HOME\"); export TERM=dumb; "

    private fun baseCommand(innerScript: String): List<String> = listOf(
        toolchain.base.prootBin.absolutePath,
        // No "-0" (fake root) anywhere — see ClaudeCodeProcess for the full reasoning.
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
        val proc = ProcessBuilder(baseCommand("$envPrefix codex login status 2>&1"))
            .redirectErrorStream(true)
            .withProotEnv()
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) proc.destroyForcibly()
        // `codex login status` documents "exits 0 when logged in", which is the reliable signal; the
        // printed wording is for humans and can be reworded by any release, so it only feeds `method`.
        val exitedZero = finished && proc.exitValue() == 0
        AuthStatus(
            loggedIn = exitedZero,
            method = parseMethod(output),
            raw = output,
        )
    }

    private fun parseMethod(output: String): String? = when {
        output.contains("ChatGPT", ignoreCase = true) -> "ChatGPT subscription"
        output.contains("API key", ignoreCase = true) -> "API key"
        output.contains("access token", ignoreCase = true) -> "Access token"
        else -> null
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(baseCommand("$envPrefix codex logout 2>&1"))
            .redirectErrorStream(true)
            .withProotEnv()
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            error("Codex sign-out timed out")
        }
        // Codex removes both API-key and ChatGPT credentials; treat a non-zero exit as a real failure
        // rather than deleting auth.json ourselves — the CLI owns that file's layout.
        check(proc.exitValue() == 0) {
            output.lineSequence().lastOrNull { it.isNotBlank() }?.take(240) ?: "Codex sign-out failed"
        }
    }

    /**
     * Starts `codex login --device-auth`, invoking [onPrompt] as soon as a verification URL (and, when
     * printed, a user code) has been parsed out of the process's output.
     *
     * The CLI does the polling; the returned [LoginSession] just lets the caller wait for the outcome or
     * abandon it.
     */
    suspend fun beginDeviceLogin(onPrompt: (DevicePrompt) -> Unit): LoginSession = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(baseCommand("$envPrefix codex login --device-auth 2>&1"))
            .redirectErrorStream(true)
            .withProotEnv()
            .start()
        val session = LoginSession(proc, proc.inputStream.bufferedReader())
        session.startCapturing(onPrompt)
        session
    }

    /** What the user needs in order to approve the sign-in in a browser. */
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
                        // Always scan the whole accumulated buffer, never just this chunk: the CLI
                        // flushes in small writes and a URL or code split across two reads would
                        // otherwise never match. This exact bug shipped in the Claude login flow.
                        val url = URL_REGEX.find(accumulated)?.value?.trimEnd('.', ',', ')', ']', '"')
                        if (url != null) {
                            promptEmitted = true
                            onPrompt(DevicePrompt(url, CODE_REGEX.find(accumulated)?.groupValues?.get(1)))
                        }
                    }
                }
            }, "codex-login-reader").apply { isDaemon = true }.start()
        }

        /**
         * Waits for the device-code flow to finish.
         *
         * The default is generous on purpose: unlike a redirect-based flow this cannot complete until a
         * human has opened a URL on some other device, read a code, typed it, and approved. Anything on
         * the order of the Claude flow's 30s would time out on essentially every real sign-in.
         */
        suspend fun awaitResult(timeoutMs: Long = 5 * 60_000): Boolean = withContext(Dispatchers.IO) {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext false
            }
            // Exit code is the signal; the CLI exits non-zero on a denied or expired code.
            process.exitValue() == 0
        }

        fun output(): String = synchronized(outputSoFar) { outputSoFar.toString() }

        fun cancel() {
            runCatching { process.destroyForcibly() }
        }

        private companion object {
            val URL_REGEX = Regex("https://\\S+")

            /** Device codes print in assorted layouts ("code: ABCD-EFGH", "enter the code ABCD-EFGH").
             * Matching the shape rather than the label keeps this working across wording changes; a
             * missing code is not fatal — many flows embed it in the URL instead. */
            val CODE_REGEX = Regex("\\b([A-Z0-9]{4,}-[A-Z0-9]{4,})\\b")
        }
    }
}
