package com.legion.orion.codex

import com.legion.orion.claudecode.ClaudeCodeProcess
import com.legion.orion.claudecode.ensureOrionToolClient
import com.legion.orion.claudecode.shellQuote
import com.legion.orion.claudecode.stringOrNull
import java.io.File
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
 * Runs one Codex turn per invocation via `codex exec --json`, bind-mounting the real project directory
 * into the chroot at `/root/project` so Codex's own shell/apply-patch tools act on the actual files —
 * the same working tree Claude Code, the file manager, the terminal, and the git panel all see.
 *
 * **This is deliberately NOT shaped like [ClaudeCodeProcess], because the two CLIs differ at the root.**
 * `claude -p --input-format=stream-json` is a persistent process fed one turn at a time over stdin;
 * `codex exec` is a one-shot that runs a single turn and exits, with continuity supplied by replaying a
 * thread id (`codex exec resume <threadId>`). Modelling Codex as a long-lived process would mean
 * inventing state the CLI doesn't have. The one-shot shape is strictly simpler and removes three classes
 * of bug the Claude path had to handle: a process dying mid-conversation, a turn's reader deadlocking a
 * shared lock, and options changed mid-turn tearing down a live process. It also means Android killing
 * the app can cost at most the turn in flight, never the conversation.
 *
 * The cost of the one-shot shape is per-turn startup: Codex re-reads its config and re-establishes the
 * thread on every send, which is why [turnLock] still exists — two concurrent turns against the same
 * thread id would interleave writes to the same session file.
 */
class CodexProcess(
    private val toolchain: CodexToolchain,
    private val projectDir: File,
    @Volatile private var threadId: String?,
    private val onThreadIdAssigned: (String) -> Unit,
    /** Codex model slug (e.g. "gpt-5-codex"); null = no `-m` flag, Codex uses its own default. */
    private val model: String? = null,
    /** low/medium/high/xhigh — passed as `-c model_reasoning_effort`. */
    private val effort: String = "medium",
    /** `danger-full-access` — see `SettingsRepository.codexSandbox` for why that is correct here. */
    private val sandbox: String = "danger-full-access",
    private val toolBridgePort: Int,
    private val toolBridgeToken: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val turnLock = Mutex()

    @Volatile private var process: Process? = null

    private val guestProjectDir = "/root/project"
    private val cpuWorkers = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)

    private fun buildCommand(resume: String?): List<String> {
        ensureOrionToolClient(toolchain.base)
        val innerScript = buildString {
            append("export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; ")
            append("export HOME=/root; export CODEX_HOME=/root/.codex; ")
            append("(umask 077; mkdir -p \"\$CODEX_HOME\"); export TERM=dumb; ")
            // Mirrors ClaudeCodeProcess: builds and test suites launched by the agent should use the
            // whole SoC, not one core, and nothing else sets these inside a bare chroot.
            append("export MAKEFLAGS=-j$cpuWorkers CMAKE_BUILD_PARALLEL_LEVEL=$cpuWorkers CARGO_BUILD_JOBS=$cpuWorkers; ")
            append("export GOMAXPROCS=$cpuWorkers RAYON_NUM_THREADS=$cpuWorkers OMP_NUM_THREADS=$cpuWorkers OPENBLAS_NUM_THREADS=$cpuWorkers; ")
            append("export UV_THREADPOOL_SIZE=${(cpuWorkers * 4).coerceAtMost(128)}; ")
            append("exec codex exec --json")
            append(" --sandbox ").append(sandbox)
            // Orion's blank projects are git repos (GitSnapshotter initialises one), but a project
            // cloned or restructured by the agent may not be, and Codex otherwise refuses to run
            // outside a trusted repo — which would surface as an inscrutable startup failure.
            append(" --skip-git-repo-check")
            append(" --color never")
            // The Orion tool bridge instructions. Codex has no `--append-system-prompt`; this config key
            // is its documented equivalent (appends to, rather than replaces, the default prompt —
            // `model_instructions_file` would replace it and lose Codex's own tool guidance).
            append(" -c developer_instructions=").append(shellQuote(ClaudeCodeProcess.ORION_AGENT_INSTRUCTIONS))
            append(" -c model_reasoning_effort=").append(shellQuote(effort))
            // Without this, reasoning items carry no text and the thinking blocks in Orion's UI would
            // render empty — Codex hides raw reasoning by default.
            append(" -c show_raw_agent_reasoning=true")
            if (model != null) append(" -m ").append(shellQuote(model))
            if (resume != null) append(" resume ").append(shellQuote(resume))
            // Prompt arrives on stdin. Passing it as an argv element instead would splice arbitrary user
            // text into a bash -c script, and `-` is the documented way to read it from stdin.
            append(" -")
        }
        return listOf(
            toolchain.base.prootBin.absolutePath,
            // No "-0" (fake root) — consistent with every other proot call site here.
            "-r", toolchain.base.rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${projectDir.absolutePath}:$guestProjectDir",
            "-w", guestProjectDir,
            "/bin/bash", "-c", innerScript,
        )
    }

    /**
     * Sends one user turn and returns a Flow of the raw parsed JSONL events for that turn, emitted live
     * as Codex produces them (see [CodexEventMapper] for translating these into `UiPart`/`ToolView`).
     *
     * If a stored thread id turns out to be unusable, the turn is silently retried once as a fresh
     * thread. This is a real case rather than defensive padding: `codex exec resume` on an unknown id
     * does not fail loudly, and threads created with `--ephemeral` can never be resumed at all — so a
     * stale id can otherwise strand a project's chat permanently.
     */
    fun sendTurn(userText: String): Flow<JsonObject> = channelFlow {
        turnLock.withLock {
            val attemptedResume = threadId != null
            val failed = runOneAttempt(userText) { send(it) }
            if (failed && attemptedResume) {
                // runOneAttempt already emitted an error event describing the failure, so the retry needs
                // no announcement of its own — a second failure emits its own event the same way.
                threadId = null
                runOneAttempt(userText) { send(it) }
            }
        }
    }

    /** @return true when the attempt produced no usable turn (so a resume-less retry is worth trying). */
    private suspend fun runOneAttempt(
        userText: String,
        onEvent: suspend (JsonObject) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(buildCommand(resume = threadId))
            // Keep stderr separate: Codex writes human-facing progress and warnings there, and folding
            // it into stdout would inject non-JSON lines into the event stream.
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = toolchain.base.loaderBin.absolutePath
                environment()["PROOT_LOADER_32"] = toolchain.base.loader32Bin.absolutePath
                environment()["PROOT_TMP_DIR"] = toolchain.base.prootTmpDir.absolutePath
                environment()["LD_LIBRARY_PATH"] = toolchain.base.libDir.absolutePath
                environment()["ORION_TOOL_PORT"] = toolBridgePort.toString()
                environment()["ORION_TOOL_TOKEN"] = toolBridgeToken
                environment()["ORION_PROJECT_HOST"] = projectDir.absolutePath
            }
            .start()
        process = proc

        // Drain stderr on a separate daemon thread. This is not optional bookkeeping: a process whose
        // stderr pipe fills up blocks forever on write, and Codex is chatty enough there to hit a 64KB
        // pipe buffer on a long turn. Kept for the failure message rather than displayed as output.
        val stderr = StringBuilder()
        Thread({
            runCatching {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderr) { if (stderr.length < 8192) stderr.append(line).append('\n') }
                }
            }
        }, "codex-stderr").apply { isDaemon = true }.start()

        runCatching {
            proc.outputStream.bufferedWriter().use { w ->
                w.write(userText)
                // Closing stdin is what tells Codex the prompt is complete — without it the CLI waits
                // for EOF and the turn never begins.
                w.flush()
            }
        }

        var sawTurnEvent = false
        var sawThread = false
        proc.inputStream.bufferedReader().use { r ->
            while (true) {
                val line = runCatching { r.readLine() }.getOrNull() ?: break
                if (line.isBlank()) continue
                val obj = runCatching { json.parseToJsonElement(line) as JsonObject }.getOrNull() ?: continue

                when (obj.stringOrNull("type")) {
                    "thread.started" -> {
                        sawThread = true
                        val id = obj.stringOrNull("thread_id")
                        if (id != null && id != threadId) {
                            threadId = id
                            onThreadIdAssigned(id)
                        }
                    }
                    "turn.completed", "turn.failed" -> sawTurnEvent = true
                }
                onEvent(obj)
            }
        }
        proc.waitFor()
        process = null

        val exit = proc.exitValue()
        val hadOutput = sawThread || sawTurnEvent
        if (!hadOutput) {
            val detail = synchronized(stderr) { stderr.toString() }.trim().takeLast(600)
            onEvent(
                errorEvent(
                    buildString {
                        append("Codex exited with code ").append(exit).append(" without starting a turn.")
                        if (detail.isNotEmpty()) append('\n').append(detail)
                    },
                ),
            )
        }
        // Only a total absence of turn events justifies the fresh-thread retry. A turn that ran and
        // failed on its own merits (a compile error, a refusal) must not be silently run a second time.
        !hadOutput
    }

    /** A synthetic event in the same envelope shape as the real ones, so [CodexEventMapper] has exactly
     * one code path for surfacing failures and this class never needs to know about `UiPart`. */
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
