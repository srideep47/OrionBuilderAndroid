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
    private val toolBridgePort: Int,
    private val toolBridgeToken: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val turnLock = Mutex()

    @Volatile private var process: Process? = null
    private var writer: Writer? = null
    private var reader: BufferedReader? = null

    private val guestProjectDir = "/root/project"
    private val cpuWorkers = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)

    private fun buildCommand(resume: String?): List<String> {
        ensureOrionIntegrationFiles()
        val effectivePermissionMode = if (permissionMode == "manual") "default" else permissionMode
        val innerScript = buildString {
            append("export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; ")
            append("export HOME=/root; export TERM=dumb; ")
            append("export MAKEFLAGS=-j$cpuWorkers CMAKE_BUILD_PARALLEL_LEVEL=$cpuWorkers CARGO_BUILD_JOBS=$cpuWorkers; ")
            append("export GOMAXPROCS=$cpuWorkers RAYON_NUM_THREADS=$cpuWorkers OMP_NUM_THREADS=$cpuWorkers OPENBLAS_NUM_THREADS=$cpuWorkers; ")
            append("export UV_THREADPOOL_SIZE=${(cpuWorkers * 4).coerceAtMost(128)}; ")
            append("exec claude -p --input-format=stream-json --output-format=stream-json ")
            append("--include-partial-messages --permission-mode ").append(effectivePermissionMode)
            append(" --effort ").append(effort)
            append(" --verbose")
            append(" --settings /root/.claude/orion-settings.json")
            // Fresh Claude projects intentionally begin as unclassified empty directories. Godot's
            // scaffold is installed by `godot-launch`, so gating these instructions on an existing
            // stackType/project.godot creates a catch-22. The idle bridge costs nothing; advertise it
            // to every project and let Claude use it only when the request is about Godot.
            val systemPrompt = PREVIEW_SYSTEM_PROMPT + " " + GODOT_SYSTEM_PROMPT
            append(" --append-system-prompt ").append(shq(systemPrompt))
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

        /** Shared with Codex (see `codex/CodexProcess.kt`), which injects it via
         * `-c developer_instructions=…` instead of `--append-system-prompt`. Both engines drive the
         * exact same `orion-tool` bridge, so the instructions describing it must not fork. */
        internal val ORION_AGENT_INSTRUCTIONS: String
            get() = PREVIEW_SYSTEM_PROMPT + " " + GODOT_SYSTEM_PROMPT

        internal const val PREVIEW_SYSTEM_PROMPT =
            "When the user wants to preview a web project on this device, always serve it in the " +
                "background on port $PREVIEW_PORT (e.g. python3 -m http.server $PREVIEW_PORT for " +
                "static HTML/CSS/JS, or the project's dev server with its port overridden to " +
                "$PREVIEW_PORT, e.g. npm run dev -- --port $PREVIEW_PORT for Vite, PORT=$PREVIEW_PORT " +
                "npm start for Create React App). Start it with Bash in the background (trailing &) so " +
                "it keeps running after your reply. The user's preview pane only ever looks at " +
                "http://127.0.0.1:$PREVIEW_PORT/ - never use a different port. " +
                "Orion also provides the Bash command orion-tool. Use orion-tool status to inspect " +
                "availability. For web QA use preview-qa, preview-tree, preview-console, preview-eval, " +
                "and preview-screenshot; then use Read on /root/project/.orion/preview.png to inspect " +
                "it visually. For installed Android app QA, device verification must be enabled by " +
                "the user in Android Accessibility settings; then use android-project, android-build, android-launch, " +
                "device-tree, device-click/device-type/device-tap, device-screenshot, and use Read on " +
                "/root/project/.orion/device.png. Never claim visual UI verification without actually " +
                "reading the screenshot and checking the relevant UI state. Run orion-tool help for syntax."

        internal const val GODOT_SYSTEM_PROMPT =
            "This project has an embedded Godot game engine (isolated process, zero footprint when " +
                "idle) with full AI control via orion-tool. Every orion-tool operation is a Bash/shell " +
                "command, not a native Claude tool: invoke it directly with Bash and NEVER use ToolSearch " +
                "to look for Godot tools. Godot intentionally is NOT a godot/godot4 executable inside the " +
                "Linux proot and is NOT an MCP server: it runs in Orion's Android engine process, so never " +
                "use which/command -v or inspect .mcp.json to decide whether it is available. Shell command " +
                "names use hyphens (for example `orion-tool godot-launch`), even when the user's prompt uses " +
                "tool-style underscore names such as godot_launch. Do not create Monitor or ScheduleWakeup loops for engine startup; " +
                "godot-launch itself waits for the bridge and either returns ready or a concrete error. " +
                "Workflow: orion-tool godot-launch starts " +
                "the active Orion project (required first); godot-capabilities reports exact bridge " +
                "operations and godot-classdb [class] exposes the live ClassDB, so discover APIs " +
                "instead of guessing. orion-tool godot-scene-tree lists every live node's path/class " +
                "under /root — do this before touching anything. orion-tool godot-list-properties " +
                "<path> and godot-list-methods <path> show exactly what a node supports before you " +
                "call godot-get-property/godot-set-property/godot-call-method on it. " +
                "godot-set-property accepts JSON values, including [x,y,z] arrays for " +
                "Vector2/Vector3/Color properties (auto-converted). godot-create-node " +
                "<parent_path> <class_name> [name] adds any built-in Godot class as a child; for " +
                "custom scripted behavior, write a .gd file with the normal file tools (project-relative, " +
                "under res://) then attach it with godot-reload-script <path> <script_path> — this " +
                "snapshots and restores the node's property values across the script swap, so " +
                "in-progress state survives. User-supplied 3D models, textures, and audio live under " +
                "res://assets/models, res://assets/textures, and res://assets/audio respectively — " +
                "run e.g. `find /root/project/assets` before assuming no assets exist, and reference " +
                "a found file by its res:// path (e.g. load(\"res://assets/models/robot.glb\")) rather " +
                "than building placeholder-only scenes when a real asset is already available. " +
                "godot-delete-node removes a node. godot-set-paused " +
                "true/false + godot-step [frames] lets you advance the running game frame-by-frame " +
                "for precise debugging. godot-simulate-input injects key/mouse/action input as if a " +
                "player pressed it (see 'orion-tool help' for its exact argument forms). " +
                "godot-screenshot then Read the PNG to see the actual rendered game — never claim a " +
                "visual game-dev result without doing this. godot-status checks whether the engine " +
                "and its bridge are responding. After structural edits call godot-save-scene so " +
                "created/deleted nodes persist; use godot-project-setting for project.godot values. " +
                "godot-stop hard-kills it when you're done so it costs " +
                "nothing while idle. AI-generated game content must live as new scenes/scripts inside " +
                "this one project (Android security policy blocks loading engine code from any other " +
                "project directory) — never try to create a second separate Godot project."

        const val ORION_TOOL_CLIENT = """#!/usr/bin/python3
import json, os, socket, sys

HELP = '''Orion visual/device QA commands:
  orion-tool status
  orion-tool preview-qa | preview-tree | preview-console
  orion-tool preview-screenshot [relative.png]
  orion-tool preview-eval <javascript>
  orion-tool preview-navigate <url>
  orion-tool device-screenshot [relative.png]
  orion-tool device-tree
  orion-tool device-click <text|description|view-id>
  orion-tool device-type <selector> <text>
  orion-tool device-tap <x> <y>
  orion-tool device-long-press <x> <y> [milliseconds]
  orion-tool device-swipe <x1> <y1> <x2> <y2> [milliseconds]
  orion-tool device-wait <selector> [seconds]
  orion-tool device-global <back|home|recents|notifications|quick-settings>
  orion-tool android-project
  orion-tool android-build [--install]
  orion-tool android-launch [package]
  orion-tool session-status              # internal Claude status-line transport
  orion-tool permission-request          # internal permission UI transport
  orion-tool godot-launch | godot-stop | godot-status
  orion-tool godot-scene-tree
  orion-tool godot-screenshot [relative.png]
  orion-tool godot-get-property <path> <property>
  orion-tool godot-set-property <path> <property> <json-value>
  orion-tool godot-call-method <path> <method> [json-args-array]
  orion-tool godot-list-properties <path>
  orion-tool godot-list-methods <path>
  orion-tool godot-create-node <parent-path> <class-name> [name]
  orion-tool godot-delete-node <path>
  orion-tool godot-set-paused <true|false>
  orion-tool godot-step [frames]
  orion-tool godot-simulate-input type=action action=jump pressed=true [strength=1.0]
  orion-tool godot-simulate-input type=key keycode=87 pressed=true
  orion-tool godot-reload-script <path> <script-path>
  orion-tool godot-capabilities
  orion-tool godot-classdb [class-name]
  orion-tool godot-save-scene [res://path.tscn]
  orion-tool godot-change-scene <res://path.tscn>
  orion-tool godot-project-setting <name> [json-value]
  orion-tool godot-input-actions
Screenshots default to .orion/preview.png and .orion/device.png and .orion/godot.png. Read the PNG afterward.'''

if len(sys.argv) < 2 or sys.argv[1] in ('help', '--help', '-h'):
    print(HELP); sys.exit(0)
cmd = sys.argv[1].replace('-', '_')
args = sys.argv[2:]
req = {'action': cmd, 'token': os.environ.get('ORION_TOOL_TOKEN',''), 'project': os.environ.get('ORION_PROJECT_HOST','')}
def parse_val(s):
    try: return json.loads(s)
    except Exception: return s

if cmd in ('preview_screenshot','device_screenshot','godot_screenshot') and args: req['output'] = args[0]
elif cmd == 'preview_eval': req['expression'] = ' '.join(args)
elif cmd == 'preview_navigate': req['url'] = args[0] if args else ''
elif cmd == 'device_click': req['selector'] = ' '.join(args)
elif cmd == 'device_type':
    if len(args) < 2: print('device-type needs selector and text', file=sys.stderr); sys.exit(2)
    req['selector'], req['text'] = args[0], ' '.join(args[1:])
elif cmd == 'device_tap':
    if len(args) != 2: print('device-tap needs x y', file=sys.stderr); sys.exit(2)
    req['x'], req['y'] = float(args[0]), float(args[1])
elif cmd == 'device_long_press':
    if len(args) < 2: print('device-long-press needs x y [milliseconds]', file=sys.stderr); sys.exit(2)
    req['x'], req['y'] = float(args[0]), float(args[1])
    req['duration'] = float(args[2]) if len(args) > 2 else 800
elif cmd == 'device_swipe':
    if len(args) < 4: print('device-swipe needs x1 y1 x2 y2 [milliseconds]', file=sys.stderr); sys.exit(2)
    req['from_x'], req['from_y'], req['to_x'], req['to_y'] = map(float, args[:4])
    req['duration'] = float(args[4]) if len(args) > 4 else 350
elif cmd == 'device_wait':
    if not args: print('device-wait needs selector [seconds]', file=sys.stderr); sys.exit(2)
    req['selector'] = args[0]
    req['timeout'] = float(args[1]) * 1000 if len(args) > 1 else 15000
elif cmd == 'device_global': req['global'] = args[0] if args else ''
elif cmd == 'android_build': req['install'] = str('--install' in args).lower()
elif cmd == 'android_launch' and args: req['package'] = args[0]
elif cmd == 'godot_get_property':
    if len(args) != 2: print('godot-get-property needs path property', file=sys.stderr); sys.exit(2)
    req['path'], req['property'] = args[0], args[1]
elif cmd == 'godot_set_property':
    if len(args) != 3: print('godot-set-property needs path property json-value', file=sys.stderr); sys.exit(2)
    req['path'], req['property'], req['value'] = args[0], args[1], parse_val(args[2])
elif cmd == 'godot_call_method':
    if len(args) < 2: print('godot-call-method needs path method [json-args-array]', file=sys.stderr); sys.exit(2)
    req['path'], req['method'] = args[0], args[1]
    req['args'] = json.loads(args[2]) if len(args) > 2 else []
elif cmd in ('godot_list_properties', 'godot_list_methods', 'godot_delete_node'):
    if not args: print(cmd.replace('_','-') + ' needs path', file=sys.stderr); sys.exit(2)
    req['path'] = args[0]
elif cmd == 'godot_create_node':
    if len(args) < 2: print('godot-create-node needs parent-path class-name [name]', file=sys.stderr); sys.exit(2)
    req['parent_path'], req['class_name'] = args[0], args[1]
    if len(args) > 2: req['name'] = args[2]
elif cmd == 'godot_set_paused':
    if not args: print('godot-set-paused needs true|false', file=sys.stderr); sys.exit(2)
    req['paused'] = args[0].lower()
elif cmd == 'godot_step':
    req['frames'] = int(args[0]) if args else 1
elif cmd == 'godot_simulate_input':
    if not args: print('godot-simulate-input needs type=... [key=value ...]', file=sys.stderr); sys.exit(2)
    for kv in args:
        if '=' not in kv:
            print('expected key=value, got: ' + kv, file=sys.stderr); sys.exit(2)
        k, v = kv.split('=', 1)
        # `action` already identifies the bridge command at the protocol envelope level. Keep the
        # InputMap action in a distinct field so `action=jump` cannot replace
        # `action=godot_simulate_input` before dispatch.
        if k == 'action': k = 'input_action'
        req[k] = parse_val(v)
elif cmd == 'godot_reload_script':
    if len(args) != 2: print('godot-reload-script needs path script-path', file=sys.stderr); sys.exit(2)
    req['path'], req['script_path'] = args[0], args[1]
elif cmd == 'godot_classdb' and args: req['class_name'] = args[0]
elif cmd == 'godot_save_scene' and args: req['path'] = args[0]
elif cmd == 'godot_change_scene':
    if not args: print('godot-change-scene needs res://path.tscn', file=sys.stderr); sys.exit(2)
    req['path'] = args[0]
elif cmd == 'godot_project_setting':
    if not args: print('godot-project-setting needs name [json-value]', file=sys.stderr); sys.exit(2)
    req['name'] = args[0]
    if len(args) > 1: req['value'] = parse_val(args[1])
elif cmd in ('session_status', 'permission_request'):
    try: req['data'] = json.load(sys.stdin)
    except Exception as e:
        print('Expected JSON on stdin: ' + str(e), file=sys.stderr); sys.exit(2)

try:
    port = int(os.environ.get('ORION_TOOL_PORT','0'))
    with socket.create_connection(('127.0.0.1', port), timeout=3600) as s:
        s.sendall((json.dumps(req) + '\n').encode())
        response = None
        for data in s.makefile('r'):
            item = json.loads(data)
            if item.get('event') == 'log':
                print(item.get('output',''), flush=True)
                continue
            response = item
            break
    if response is None: raise RuntimeError('Orion tool bridge closed without a result')
    print(response.get('output',''))
    sys.exit(0 if response.get('ok') else 1)
except Exception as e:
    print('Orion tool bridge error: ' + str(e), file=sys.stderr); sys.exit(1)
"""

        const val ORION_CLAUDE_SETTINGS = """{
  "statusLine": {
    "type": "command",
    "command": "orion-tool session-status >/dev/null 2>&1 || true; echo Orion",
    "refreshInterval": 2
  },
  "hooks": {
    "PermissionRequest": [
      {
        "matcher": ".*",
        "hooks": [
          {
            "type": "command",
            "command": "orion-tool permission-request",
            "timeout": 900
          }
        ]
      }
    ]
  }
}
"""
    }

    private fun ensureOrionIntegrationFiles() {
        // Shared with the Codex engine — see claudecode/OrionToolRuntime.kt.
        ensureOrionToolClient(toolchain)
        // Claude-specific: the status-line + PermissionRequest hook transport has no Codex counterpart.
        val settings = File(toolchain.rootfsDir, "root/.claude/orion-settings.json")
        settings.parentFile?.mkdirs()
        if (!settings.exists() || settings.readText() != ORION_CLAUDE_SETTINGS) {
            settings.writeText(ORION_CLAUDE_SETTINGS)
        }
    }

    private fun shq(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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
                environment()["ORION_TOOL_PORT"] = toolBridgePort.toString()
                environment()["ORION_TOOL_TOKEN"] = toolBridgeToken
                environment()["ORION_PROJECT_HOST"] = projectDir.absolutePath
            }
            .start()
        process = proc
        writer = proc.outputStream.bufferedWriter()
        reader = proc.inputStream.bufferedReader()
    }

    private class Attempt(val isStaleResumeFailure: Boolean)

    /** One full write-turn/read-until-result cycle, forwarding each parsed event to [onEvent] as soon
     * as it's read off the process's stdout — not batched until the turn finishes — so the UI renders
     * reasoning/tool calls live instead of the whole turn appearing at once at the end (a real
     * regression this fixes: [onEvent] used to be a local list appended to in this loop and only
     * forwarded to the caller after `break`, which made every turn look frozen on "Thinking…" until
     * completion). Also detects the specific "stale --resume id" failure signature (`type:"result"`
     * with `is_error:true` and `num_turns:0` — confirmed on-device: Claude Code prints `"No
     * conversation found with session ID: ..."` and exits this way when asked to resume a session
     * that doesn't exist in `~/.claude/` — which happens whenever the bundled rootfs gets wiped and
     * re-extracted, e.g. on an asset-version bump, while Room still has the old id). That failure
     * signature is only ever an init event immediately followed by an error result, so streaming it
     * live before the caller discards-and-retries is a harmless, momentary flash in that rare case —
     * far cheaper than batching every normal turn's entire output.
     */
    private suspend fun runOneAttempt(userText: String, onEvent: suspend (JsonObject) -> Unit): Attempt = withContext(Dispatchers.IO) {
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

        var staleResume = false
        while (true) {
            // A concurrent stop() (e.g. from setClaudeCodeOptions) closes this reader out from under
            // a blocked readLine(), which throws IOException rather than returning null — treat that
            // the same as a dead process instead of letting it escape uncaught.
            val line = runCatching { r.readLine() }.getOrNull()
            if (line == null) {
                // Process died mid-turn — surface as an error event and force a respawn on retry.
                process = null
                onEvent(buildJsonObject { put("type", "orion_process_died") })
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

            onEvent(obj)
            if (obj.stringOrNull("type") == "result") {
                val isError = (obj["is_error"] as? JsonPrimitive)?.contentOrNull == "true"
                val numTurns = (obj["num_turns"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: -1
                if (isError && numTurns == 0) staleResume = true
                break
            }
        }
        Attempt(staleResume)
    }

    /**
     * Sends one user turn and returns a Flow of the raw parsed stream-json events for that turn,
     * emitted live as Claude Code produces them (see `StreamJsonEventMapper` for translating these
     * into `UiPart`/`ToolView`). Completes the Flow after the turn's `type:"result"` event. Only one
     * turn may be in flight at a time — callers must await completion (or cancel) before sending the
     * next.
     *
     * If this attempt used `--resume` and it turns out to be stale, it's silently retried once from a
     * fresh session — the caller never needs to know a stale id existed.
     */
    fun sendTurn(userText: String): Flow<JsonObject> = channelFlow {
        turnLock.withLock {
            val attemptedResume = claudeSessionId != null
            var attempt = runOneAttempt(userText) { send(it) }
            if (attempt.isStaleResumeFailure && attemptedResume) {
                claudeSessionId = null
                stop()
                attempt = runOneAttempt(userText) { send(it) }
            }
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
