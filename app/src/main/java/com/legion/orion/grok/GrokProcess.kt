package com.legion.orion.grok

import com.legion.orion.claudecode.ClaudeCodeProcess
import com.legion.orion.claudecode.ensureOrionToolClient
import com.legion.orion.claudecode.shellQuote
import com.legion.orion.claudecode.stringOrNull
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Runs one Grok Build turn per invocation via `grok -p --output-format streaming-json`, bind-mounting
 * the real project directory into the chroot at `/root/project`.
 *
 * **One-shot shape (like Codex, not Claude).** `grok -p` runs a single prompt and exits; continuity
 * comes from `--resume <sessionId>`. Modelling Grok as a long-lived process would invent state the CLI
 * does not have.
 *
 * Prompt text is written to a guest temp file and passed with `--prompt-file` so arbitrary user text
 * (quotes, newlines, shell metacharacters) never gets spliced into a `bash -c` script.
 */
class GrokProcess(
    private val toolchain: GrokToolchain,
    private val projectDir: File,
    @Volatile private var sessionId: String?,
    private val onSessionIdAssigned: (String) -> Unit,
    /** null = no `-m` flag, Grok uses its own default (typically grok-build / grok-4.5). */
    private val model: String? = null,
    /** none/minimal/low/medium/high/xhigh/max — passed as `--reasoning-effort`. */
    private val effort: String = "medium",
    /** Default bypassPermissions for unattended headless tool use under proot. */
    private val permissionMode: String = "bypassPermissions",
    private val toolBridgePort: Int,
    private val toolBridgeToken: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val turnLock = Mutex()

    @Volatile private var process: Process? = null

    private val guestProjectDir = "/root/project"
    private val cpuWorkers = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)

    private fun buildCommand(resume: String?, promptGuestPath: String): List<String> {
        ensureOrionToolClient(toolchain.base)
        val innerScript = buildString {
            append("export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; ")
            append("export HOME=/root; export GROK_HOME=/root/.grok; ")
            append("(umask 077; mkdir -p \"\$GROK_HOME\"); export TERM=dumb; ")
            append("export MAKEFLAGS=-j$cpuWorkers CMAKE_BUILD_PARALLEL_LEVEL=$cpuWorkers CARGO_BUILD_JOBS=$cpuWorkers; ")
            append("export GOMAXPROCS=$cpuWorkers RAYON_NUM_THREADS=$cpuWorkers OMP_NUM_THREADS=$cpuWorkers OPENBLAS_NUM_THREADS=$cpuWorkers; ")
            append("export UV_THREADPOOL_SIZE=${(cpuWorkers * 4).coerceAtMost(128)}; ")
            append("exec grok")
            append(" --prompt-file ").append(shellQuote(promptGuestPath))
            append(" --cwd ").append(shellQuote(guestProjectDir))
            append(" --output-format streaming-json")
            append(" --always-approve")
            append(" --permission-mode ").append(shellQuote(permissionMode))
            append(" --reasoning-effort ").append(shellQuote(effort))
            append(" --rules ").append(shellQuote(ClaudeCodeProcess.ORION_AGENT_INSTRUCTIONS))
            if (model != null) append(" -m ").append(shellQuote(model))
            if (resume != null) append(" --resume ").append(shellQuote(resume))
        }
        return listOf(
            toolchain.base.prootBin.absolutePath,
            "-r", toolchain.base.rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${projectDir.absolutePath}:$guestProjectDir",
            "-w", guestProjectDir,
            "/bin/bash", "-c", innerScript,
        )
    }

    fun sendTurn(userText: String): Flow<JsonObject> = channelFlow {
        turnLock.withLock {
            val attemptedResume = sessionId != null
            val failed = runOneAttempt(userText) { send(it) }
            if (failed && attemptedResume) {
                sessionId = null
                runOneAttempt(userText) { send(it) }
            }
        }
    }

    /** @return true when the attempt produced no usable turn (stale resume may be worth retrying). */
    private suspend fun runOneAttempt(
        userText: String,
        onEvent: suspend (JsonObject) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        // Host-side prompt file inside the project (visible at /root/project/.orion/… in guest).
        val promptHost = File(projectDir, ".orion/grok-prompt-${UUID.randomUUID()}.txt")
        promptHost.parentFile?.mkdirs()
        promptHost.writeText(userText)
        val promptGuest = "$guestProjectDir/.orion/${promptHost.name}"

        try {
            val proc = ProcessBuilder(buildCommand(resume = sessionId, promptGuestPath = promptGuest))
                .redirectErrorStream(false)
                .apply {
                    environment()["PROOT_LOADER"] = toolchain.base.loaderBin.absolutePath
                    environment()["PROOT_LOADER_32"] = toolchain.base.loader32Bin.absolutePath
                    environment()["PROOT_TMP_DIR"] = toolchain.base.prootTmpDir.absolutePath
                    environment()["LD_LIBRARY_PATH"] = toolchain.base.libDir.absolutePath
                    environment()["ORION_TOOL_PORT"] = toolBridgePort.toString()
                    environment()["ORION_TOOL_TOKEN"] = toolBridgeToken
                    environment()["ORION_PROJECT_HOST"] = projectDir.absolutePath
                    environment()["GROK_HOME"] = "/root/.grok"
                    environment()["HOME"] = "/root"
                }
                .start()
            process = proc

            val stderr = StringBuilder()
            Thread({
                runCatching {
                    proc.errorStream.bufferedReader().forEachLine { line ->
                        synchronized(stderr) { if (stderr.length < 8192) stderr.append(line).append('\n') }
                    }
                }
            }, "grok-stderr").apply { isDaemon = true }.start()

            var sawTurnEvent = false
            var sawSession = false
            proc.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = runCatching { r.readLine() }.getOrNull() ?: break
                    if (line.isBlank()) continue
                    val obj = runCatching { json.parseToJsonElement(line) as JsonObject }.getOrNull() ?: continue

                    when (obj.stringOrNull("type")) {
                        "end" -> {
                            sawTurnEvent = true
                            val id = obj.stringOrNull("sessionId")
                            if (id != null && id != sessionId) {
                                sessionId = id
                                onSessionIdAssigned(id)
                            }
                        }
                        "text", "thought", "tool_call", "tool_call_update", "usage", "error" -> {
                            sawTurnEvent = true
                            // sessionId may also ride mid-stream events in some formats.
                            obj.stringOrNull("sessionId")?.let { id ->
                                if (id != sessionId) {
                                    sessionId = id
                                    onSessionIdAssigned(id)
                                    sawSession = true
                                }
                            }
                        }
                    }
                    onEvent(obj)
                }
            }
            proc.waitFor()
            process = null

            val exit = proc.exitValue()
            val hadOutput = sawTurnEvent || sawSession
            if (!hadOutput) {
                val detail = synchronized(stderr) { stderr.toString() }.trim().takeLast(600)
                val lower = detail.lowercase()
                val resumeFailed = sessionId != null && (
                    lower.contains("session") && (lower.contains("not found") || lower.contains("unknown"))
                    )
                onEvent(
                    errorEvent(
                        buildString {
                            append("Grok Build exited with code ").append(exit).append(" without starting a turn.")
                            if (detail.isNotEmpty()) append('\n').append(detail)
                        },
                    ),
                )
                // Retry only when we believe the resume id was bad.
                return@withContext resumeFailed || !hadOutput
            }
            false
        } finally {
            runCatching { promptHost.delete() }
        }
    }

    private fun errorEvent(message: String): JsonObject = buildJsonObject {
        put("type", "orion_error")
        put("message", message)
    }

    fun stop() {
        runCatching { process?.destroyForcibly() }
        process = null
    }

    fun isAlive(): Boolean = process?.isAlive == true
}
