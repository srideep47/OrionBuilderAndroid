package com.legion.orion.claudecode

import java.io.BufferedReader
import java.io.File
import java.io.Writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Safe string read for a top-level field that is expected to be a JSON string, if present. */
internal fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * Owns one persistent `proot`-wrapped `claude -p --input-format=stream-json
 * --output-format=stream-json` subprocess for a single project, bind-mounting the real project
 * directory into the chroot at `/root/project` so Claude Code's own tool calls (Read/Write/Edit/Bash/
 * Grep — it executes these itself; it does not report them back for Orion's ToolRegistry to run) act
 * on the actual on-device files.
 *
 * One process serves MANY conversation turns: [sendTurn] writes one stream-json input line to the
 * already-running process's stdin and returns a [Flow] of parsed JSON events for that turn, ending at
 * the turn's `type:"result"` marker. This is new territory for this codebase — contrast
 * [com.legion.orion.runtime.buildtools.GradleBuildRunner], which always runs a process to completion —
 * so stdin/stdout are held open across many calls via the [Process]'s own streams directly (a naive
 * `cat fifo | adb shell` shell pipe used during manual testing got flaky after the first write; direct
 * `Process` stream access does not have that issue).
 *
 * Claude Code requires `--verbose` alongside `-p --output-format=stream-json` or it refuses to start.
 * A non-interactive `--permission-mode` is required since there is no human to answer a permission
 * prompt; `bypassPermissions` is the only one that actually runs Bash unattended (see the field doc
 * below). On first launch for a project no `--session-id`/`--resume` is passed — Claude
 * Code assigns one and reports it in the initial `system`/`init` event, which is captured via
 * [onSessionIdAssigned] so the caller can persist it (see `ClaudeCodeSessionDao`) and pass it as
 * `--resume` next time this process needs to be (re)started (e.g. after the OS kills the app).
 */
class ClaudeCodeProcess(
    private val toolchain: ClaudeCodeToolchain,
    private val projectDir: File,
    @Volatile private var claudeSessionId: String?,
    private val onSessionIdAssigned: (String) -> Unit,
    /** Model alias ("sonnet"/"opus"/"haiku"/"fable") or full name; null = account default, no `--model`. */
    private val model: String? = null,
    /** One of: low, medium, high, xhigh, max. */
    private val effort: String = "medium",
    /** One of: acceptEdits, auto, bypassPermissions, manual, dontAsk, plan. `bypassPermissions` is the
     * only mode that actually runs Bash commands unattended — `acceptEdits` auto-approves file edits
     * but still blocks on "This command requires approval" for Bash, which nothing can ever answer in
     * this headless setup, discovered when a preview dev-server launch silently failed this way. */
    private val permissionMode: String = "bypassPermissions",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val turnLock = Mutex()

    @Volatile private var process: Process? = null
    private var writer: Writer? = null
    private var reader: BufferedReader? = null

    private val guestProjectDir = "/root/project"

    private fun buildCommand(resume: String?): List<String> {
        val innerScript = buildString {
            append("export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; ")
            append("export HOME=/root; export TERM=dumb; ")
            append("exec claude -p --input-format=stream-json --output-format=stream-json ")
            append("--include-partial-messages --permission-mode ").append(permissionMode)
            append(" --effort ").append(effort)
            append(" --verbose")
            append(" --append-system-prompt \"").append(PREVIEW_SYSTEM_PROMPT).append("\"")
            if (model != null) append(" --model ").append(model)
            if (resume != null) append(" --resume ").append(resume)
        }
        return listOf(
            toolchain.prootBin.absolutePath,
            // No "-0" (fake root): bypassPermissions explicitly refuses to run as root ("cannot be
            // used with root/sudo privileges for security reasons") — proot's real underlying file
            // access is governed by the actual Android app UID regardless of what identity it reports,
            // so dropping the fake-root flag changes nothing about what the sandbox can touch.
            "-r", toolchain.rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${projectDir.absolutePath}:$guestProjectDir",
            "-w", guestProjectDir,
            "/bin/bash", "-c", innerScript,
        )
    }

    companion object {
        /** Orion's Preview tab always points at this fixed port for Claude Code chats (see
         * `PreviewController`/`ChatViewModel`) — instructing a fixed port here avoids needing to
         * detect which port an arbitrary framework's dev server picked. */
        const val PREVIEW_PORT = 8073

        private const val PREVIEW_SYSTEM_PROMPT =
            "When the user wants to preview a web project on this device, always serve it in the " +
                "background on port $PREVIEW_PORT (e.g. python3 -m http.server $PREVIEW_PORT for " +
                "static HTML/CSS/JS, or the project's dev server with its port overridden to " +
                "$PREVIEW_PORT, e.g. npm run dev -- --port $PREVIEW_PORT for Vite, PORT=$PREVIEW_PORT " +
                "npm start for Create React App). Start it with Bash in the background (trailing &) so " +
                "it keeps running after your reply. The user's preview pane only ever looks at " +
                "http://127.0.0.1:$PREVIEW_PORT/ - never use a different port."
    }

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        val cmd = buildCommand(resume = claudeSessionId)
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = toolchain.loaderBin.absolutePath
                environment()["PROOT_LOADER_32"] = toolchain.loader32Bin.absolutePath
                environment()["PROOT_TMP_DIR"] = toolchain.prootTmpDir.absolutePath
                environment()["LD_LIBRARY_PATH"] = toolchain.libDir.absolutePath
            }
            .start()
        process = proc
        writer = proc.outputStream.bufferedWriter()
        reader = proc.inputStream.bufferedReader()
    }

    private class Attempt(val events: List<JsonObject>, val isStaleResumeFailure: Boolean)

    /** One full write-turn/read-until-result cycle. Detects the specific "stale --resume id" failure
     * signature (`type:"result"` with `is_error:true` and `num_turns:0` — confirmed on-device: Claude
     * Code prints `"No conversation found with session ID: ..."` and exits this way when asked to
     * resume a session that doesn't exist in `~/.claude/` — which happens whenever the bundled rootfs
     * gets wiped and re-extracted, e.g. on an asset-version bump, while Room still has the old id). */
    private suspend fun runOneAttempt(userText: String): Attempt = withContext(Dispatchers.IO) {
        ensureStarted()
        val w = writer ?: error("Claude Code process failed to start")
        val r = reader ?: error("Claude Code process failed to start")

        val payload = buildJsonObject {
            put("type", "user")
            put("message", buildJsonObject {
                put("role", "user")
                put("content", userText)
            })
        }
        w.write(json.encodeToString(JsonObject.serializer(), payload))
        w.write("\n")
        w.flush()

        val events = mutableListOf<JsonObject>()
        var staleResume = false
        while (true) {
            // A concurrent stop() (e.g. from setClaudeCodeOptions) closes this reader out from under
            // a blocked readLine(), which throws IOException rather than returning null — treat that
            // the same as a dead process instead of letting it escape uncaught.
            val line = runCatching { r.readLine() }.getOrNull()
            if (line == null) {
                // Process died mid-turn — surface as an error event and force a respawn on retry.
                process = null
                events.add(buildJsonObject { put("type", "orion_process_died") })
                break
            }
            if (line.isBlank()) continue
            val obj = runCatching { json.parseToJsonElement(line) as JsonObject }.getOrNull() ?: continue

            if (obj.stringOrNull("type") == "system" && obj.stringOrNull("subtype") == "init") {
                val sid = obj.stringOrNull("session_id")
                if (sid != null && claudeSessionId == null) {
                    claudeSessionId = sid
                    onSessionIdAssigned(sid)
                }
            }

            events.add(obj)
            if (obj.stringOrNull("type") == "result") {
                val isError = (obj["is_error"] as? JsonPrimitive)?.contentOrNull == "true"
                val numTurns = (obj["num_turns"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: -1
                if (isError && numTurns == 0) staleResume = true
                break
            }
        }
        Attempt(events, staleResume)
    }

    /**
     * Sends one user turn and returns a Flow of the raw parsed stream-json events for that turn
     * (see `StreamJsonEventMapper` for translating these into `UiPart`/`ToolView`). Completes the
     * Flow after the turn's `type:"result"` event. Only one turn may be in flight at a time — callers
     * must await completion (or cancel) before sending the next.
     *
     * If this attempt used `--resume` and it turns out to be stale, the failed attempt is discarded
     * (never forwarded to the caller) and silently retried once from a fresh session — the caller
     * never needs to know a stale id existed.
     */
    fun sendTurn(userText: String): Flow<JsonObject> = channelFlow {
        turnLock.withLock {
            val attemptedResume = claudeSessionId != null
            var attempt = runOneAttempt(userText)
            if (attempt.isStaleResumeFailure && attemptedResume) {
                claudeSessionId = null
                stop()
                attempt = runOneAttempt(userText)
            }
            attempt.events.forEach { send(it) }
        }
    }

    fun stop() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { process?.destroy() }
        process = null
    }

    fun isAlive(): Boolean = process?.isAlive == true
}
