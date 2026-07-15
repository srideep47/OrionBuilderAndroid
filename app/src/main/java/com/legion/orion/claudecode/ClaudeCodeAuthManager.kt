package com.legion.orion.claudecode

import java.io.BufferedReader
import java.io.Writer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-shot helper for the `claude auth login --claudeai` OAuth flow (subscription-based sign-in —
 * `--claudeai` is explicitly "Use Claude subscription", as opposed to `--console` for pay-per-token
 * API billing) and for checking current auth status. Not scoped to any project; runs with `/root` as
 * the chroot working directory since login doesn't touch project files.
 *
 * The actual browser sign-in (email/password/SSO) is the user's own action in their own browser —
 * this class only launches the helper process, surfaces the printed OAuth URL, and relays back
 * whatever authorization code the user copies from the browser once they've completed it themselves.
 */
class ClaudeCodeAuthManager(private val toolchain: ClaudeCodeToolchain) {

    data class AuthStatus(
        val loggedIn: Boolean,
        val email: String?,
        val subscriptionType: String?,
        val raw: String,
    )

    private fun baseCommand(innerScript: String): List<String> = listOf(
        toolchain.prootBin.absolutePath,
        // No "-0" (fake root) — see ClaudeCodeProcess for why this is dropped everywhere now.
        "-r", toolchain.rootfsDir.absolutePath,
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        "-w", "/root",
        "/bin/bash", "-c", innerScript,
    )

    private fun ProcessBuilder.withProotEnv(): ProcessBuilder = apply {
        environment()["PROOT_LOADER"] = toolchain.loaderBin.absolutePath
        environment()["PROOT_LOADER_32"] = toolchain.loader32Bin.absolutePath
        environment()["PROOT_TMP_DIR"] = toolchain.prootTmpDir.absolutePath
        environment()["LD_LIBRARY_PATH"] = toolchain.libDir.absolutePath
    }

    private val envPrefix =
        "export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export HOME=/root; "

    suspend fun status(): AuthStatus = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(baseCommand("$envPrefix claude auth status 2>&1"))
            .redirectErrorStream(true)
            .withProotEnv()
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor(30, TimeUnit.SECONDS)
        parseStatus(output)
    }

    private fun parseStatus(output: String): AuthStatus = AuthStatus(
        loggedIn = Regex("\"loggedIn\"\\s*:\\s*true").containsMatchIn(output),
        email = Regex("\"email\"\\s*:\\s*\"([^\"]+)\"").find(output)?.groupValues?.get(1),
        subscriptionType = Regex("\"subscriptionType\"\\s*:\\s*\"([^\"]+)\"").find(output)?.groupValues?.get(1),
        raw = output,
    )

    /**
     * Starts `claude auth login --claudeai`, invoking [onUrl] once with the OAuth URL to hand to a
     * browser. Returns a [LoginSession] the caller drives: show the URL, let the user complete sign-in
     * themselves, then call [LoginSession.submitCode] with whatever code the browser's redirect page
     * shows (if any — some flows complete without one), then [LoginSession.awaitResult].
     */
    suspend fun beginLogin(onUrl: (String) -> Unit): LoginSession = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(baseCommand("$envPrefix claude auth login --claudeai"))
            .redirectErrorStream(true)
            .withProotEnv()
            .start()
        val session = LoginSession(proc, proc.inputStream.bufferedReader(), proc.outputStream.bufferedWriter())
        session.startCapturing(onUrl)
        session
    }

    class LoginSession internal constructor(
        private val process: Process,
        private val reader: BufferedReader,
        private val writer: Writer,
    ) {
        private val urlRegex = Regex("https://\\S+")
        @Volatile private var urlEmitted = false
        private val outputSoFar = StringBuilder()

        internal fun startCapturing(onUrl: (String) -> Unit) {
            Thread({
                val buf = CharArray(4096)
                while (true) {
                    val n = runCatching { reader.read(buf) }.getOrDefault(-1)
                    if (n < 0) break
                    val chunk = String(buf, 0, n)
                    val accumulated = synchronized(outputSoFar) {
                        outputSoFar.append(chunk)
                        outputSoFar.toString()
                    }
                    if (!urlEmitted) {
                        // Search the full accumulated output, not just this chunk — the URL can be
                        // split across two reads if the CLI flushes stdout in smaller writes.
                        urlRegex.find(accumulated)?.let { match ->
                            urlEmitted = true
                            onUrl(match.value.trimEnd('.', ')', ']'))
                        }
                    }
                }
            }, "claude-login-reader").apply { isDaemon = true }.start()
        }

        /** Relays the code the user pasted back from the browser into the waiting process's stdin. */
        suspend fun submitCode(code: String) = withContext(Dispatchers.IO) {
            writer.write(code)
            writer.write("\n")
            writer.flush()
        }

        /** Waits (bounded) for the login process to finish, reporting whether it looked successful. */
        suspend fun awaitResult(timeoutMs: Long = 30_000): Boolean = withContext(Dispatchers.IO) {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val text = synchronized(outputSoFar) { outputSoFar.toString() }
            finished && (text.contains("Login successful") || text.contains("already logged in", ignoreCase = true))
        }

        fun cancel() {
            runCatching { process.destroy() }
        }
    }
}
