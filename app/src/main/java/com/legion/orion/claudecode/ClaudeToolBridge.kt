package com.legion.orion.claudecode

import android.content.Context
import android.content.Intent
import com.legion.orion.automation.DeviceAutomationController
import com.legion.orion.build.ApkBuildController
import com.legion.orion.build.AndroidProjectLocator
import com.legion.orion.feature.preview.PreviewController
import com.legion.orion.internet.DevIdentityStore
import com.legion.orion.internet.InternetManager
import com.legion.orion.runtime.GodotController
import com.legion.orion.runtime.GodotProcessController
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ClaudeRateLimitTelemetry(
    val usedPercentage: Double?,
    val resetsAtEpochSeconds: Long?,
)

data class ClaudeSessionTelemetry(
    val projectPath: String,
    val sessionId: String?,
    val modelName: String?,
    val contextUsedTokens: Long,
    val contextWindowTokens: Long,
    val contextUsedPercentage: Double?,
    val contextRemainingPercentage: Double?,
    val costUsd: Double?,
    val durationMs: Long?,
    val fiveHour: ClaudeRateLimitTelemetry,
    val sevenDay: ClaudeRateLimitTelemetry,
    val receivedAt: Long = System.currentTimeMillis(),
)

data class ClaudePermissionRequest(
    val id: String,
    val projectPath: String,
    val sessionId: String?,
    val toolName: String,
    val toolInput: JsonObject,
    val permissionMode: String?,
    val suggestions: JsonArray?,
)

enum class ClaudePermissionDecision { ALLOW_ONCE, ALLOW_SESSION, DENY }

/**
 * Loopback-only command bridge used by `orion-tool` inside Claude's proot. Claude Code cannot call
 * Orion's Kotlin AgentTool registry directly, so its Bash tool talks to this bridge. A per-process
 * random token is required because Android apps share the host network namespace.
 */
@Singleton
class ClaudeToolBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preview: PreviewController,
    private val device: DeviceAutomationController,
    private val apkBuilder: ApkBuildController,
    private val godot: GodotController,
    private val godotProcess: GodotProcessController,
    private val internet: InternetManager,
    private val devIdentities: DevIdentityStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var server: ServerSocket? = null
    private val permissionWaiters = ConcurrentHashMap<String, CompletableDeferred<ClaudePermissionDecision>>()
    private val _pendingPermissions = MutableStateFlow<List<ClaudePermissionRequest>>(emptyList())
    private val _sessionTelemetry = MutableStateFlow<Map<String, ClaudeSessionTelemetry>>(emptyMap())

    val pendingPermissions = _pendingPermissions.asStateFlow()
    val sessionTelemetry = _sessionTelemetry.asStateFlow()

    val token: String = UUID.randomUUID().toString().replace("-", "")
    val port: Int get() = server?.localPort ?: 0

    fun start(): Int = synchronized(lock) {
        val activePort = server?.takeIf { !it.isClosed }?.localPort
            // Pin IPv4: Android may resolve getLoopbackAddress() to ::1, while proot's Python
            // socket client resolves 127.0.0.1. Binding ::1 made the real Claude command fail even
            // though adb's port forward made host-side smoke tests appear healthy.
            ?: ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).also { socket ->
                server = socket
                scope.launch {
                    while (!socket.isClosed) {
                        val client = runCatching { socket.accept() }.getOrNull() ?: break
                        launch { handle(client) }
                    }
                }
            }.localPort
        File(context.filesDir, "claudecode/tool-bridge.json").apply {
            parentFile?.mkdirs()
            writeText("{\"port\":$activePort,\"token\":\"$token\"}\n")
        }
        activePort
    }

    private fun handle(socket: Socket) = socket.use { client ->
        client.soTimeout = 60 * 60 * 1000
        val line = client.getInputStream().bufferedReader().readLine()
        val request = line?.takeIf { it.length <= MAX_REQUEST_CHARS }
            ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        client.getOutputStream().bufferedWriter().use { writer ->
            val emitLog: (String) -> Unit = { output ->
                writer.write(json.encodeToString(JsonObject.serializer(), progress(output)))
                writer.write("\n")
                writer.flush()
            }
            val response = when {
                request == null -> failure("Invalid or oversized request")
                request.str("token") != token -> failure("Unauthorized")
                else -> runBlocking { execute(request, emitLog) }
            }
            writer.write(json.encodeToString(JsonObject.serializer(), response))
            writer.write("\n")
        }
    }

    private suspend fun execute(request: JsonObject, emitLog: (String) -> Unit): JsonObject {
        val action = request.str("action") ?: return failure("Missing action")
        return runCatching {
            when (action) {
                "status" -> success(
                    "Orion tool bridge ready\n" +
                        "preview=${if (preview.isAttached) "attached" else "not-open"}\n" +
                        "device-verification=${if (device.connected.value) "enabled" else "disabled"}\n" +
                        "internet=web_search,web_fetch,web_download,list_dev_identities",
                )
                "web_search" -> {
                    val query = request.str("query") ?: error("Missing query")
                    val max = request.number("max_results")?.toInt() ?: 8
                    val hits = internet.search(query, max).getOrThrow()
                    success(internet.formatSearch(hits))
                }
                "web_fetch" -> {
                    val url = request.str("url") ?: error("Missing url")
                    val mode = request.str("mode") ?: "text"
                    val maxChars = request.number("max_chars")?.toInt()
                        ?: InternetManager.DEFAULT_FETCH_CHARS
                    val page = internet.fetch(url, mode, maxChars).getOrThrow()
                    success(internet.formatFetch(page))
                }
                "web_download" -> {
                    val url = request.str("url") ?: error("Missing url")
                    val path = request.str("path") ?: error("Missing path")
                    val maxBytes = request.number("max_bytes")?.toLong()
                        ?: InternetManager.DEFAULT_DOWNLOAD_BYTES
                    val project = projectDir(request)
                    val result = internet.download(url, project, path, maxBytes).getOrThrow()
                    success(
                        internet.formatDownload(result) +
                            "\nGuest path: ${guestPath(result.path, request)}",
                    )
                }
                "list_dev_identities" -> success(devIdentities.formatPublicList(devIdentities.list()))
                "session_status" -> {
                    val project = projectDir(request).canonicalPath
                    val data = request["data"] as? JsonObject ?: error("Missing status data")
                    _sessionTelemetry.value = _sessionTelemetry.value + (project to parseTelemetry(project, data))
                    success("Session telemetry accepted")
                }
                "permission_request" -> {
                    val project = projectDir(request).canonicalPath
                    val data = request["data"] as? JsonObject ?: error("Missing permission request")
                    success(awaitPermission(project, data))
                }
                "preview_screenshot" -> {
                    val out = outputFile(request, "preview.png")
                    check(preview.screenshot(out)) { "Preview is not open or has not rendered" }
                    success("Saved ${guestPath(out, request)} (${out.length()} bytes). Use Read on this PNG to inspect it visually.")
                }
                "preview_tree" -> success(preview.accessibilityTree().orEmpty().ifBlank { "(empty preview tree)" })
                "preview_eval" -> success(preview.eval(request.str("expression") ?: error("Missing expression")) ?: "null")
                "preview_navigate" -> {
                    preview.loadUrl(request.str("url") ?: error("Missing url"))
                    success("Navigation requested")
                }
                "preview_console" -> success(
                    preview.consoleLog().takeLast(100).joinToString("\n") {
                        "[${it.level}] ${it.message}${if (it.source.isNotBlank()) " (${it.source}:${it.line})" else ""}"
                    }.ifBlank { "(no console output)" },
                )
                "preview_qa" -> {
                    val diag = preview.diagnostics()
                    val failedRequests = diag.failedRequests.filterNot { it.contains("/favicon.ico", ignoreCase = true) }
                    val tree = preview.accessibilityTree().orEmpty()
                    val render = preview.renderReport().orEmpty()
                    val renderObject = runCatching {
                        Json.parseToJsonElement(render) as? JsonObject
                    }.getOrNull()
                    val cssRules = renderObject?.str("cssRules")?.toIntOrNull() ?: -1
                    val styleTags = renderObject?.str("styleTags")?.toIntOrNull() ?: 0
                    val styleLinks = renderObject?.get("links") as? kotlinx.serialization.json.JsonArray
                    val problems = buildList {
                        if (!preview.isAttached) add("preview is not open")
                        if (diag.consoleErrors.isNotEmpty()) add("console errors: ${diag.consoleErrors.joinToString(" | ")}")
                        if (failedRequests.isNotEmpty()) add("failed requests: ${failedRequests.joinToString(" | ")}")
                        if (tree.isBlank()) add("no accessible page content")
                        if (cssRules == 0 && ((styleLinks != null && styleLinks.isNotEmpty()) || styleTags > 0)) {
                            add("page is unstyled: styles are present but 0 CSS rules applied")
                        }
                        styleLinks?.forEach { link ->
                            val linkObject = link as? JsonObject
                            if (linkObject?.str("loaded") == "false") {
                                add("stylesheet '${linkObject.str("href")}' failed to apply")
                            }
                        }
                    }
                    check(problems.isEmpty()) { problems.joinToString("\n") }
                    success("QA PASSED\nrender=$render\naccessibility:\n${tree.take(8000)}")
                }
                "device_screenshot" -> {
                    val out = outputFile(request, "device.png")
                    device.screenshot(out).getOrThrow()
                    success("Saved ${guestPath(out, request)} (${out.length()} bytes). Use Read on this PNG to inspect the actual device screen.")
                }
                "device_tree" -> success(device.uiTree().getOrThrow())
                "device_click" -> {
                    device.click(request.str("selector") ?: error("Missing selector")).getOrThrow()
                    success("Clicked matching UI")
                }
                "device_type" -> {
                    device.type(
                        request.str("selector") ?: error("Missing selector"),
                        request.str("text") ?: error("Missing text"),
                    ).getOrThrow()
                    success("Text entered")
                }
                "device_tap" -> {
                    val x = request.number("x") ?: error("Missing x")
                    val y = request.number("y") ?: error("Missing y")
                    device.tap(x, y).getOrThrow()
                    success("Tapped $x,$y")
                }
                "device_long_press" -> {
                    val x = request.number("x") ?: error("Missing x")
                    val y = request.number("y") ?: error("Missing y")
                    val duration = (request.number("duration") ?: 800f).toLong()
                    device.longPress(x, y, duration).getOrThrow()
                    success("Long-pressed $x,$y")
                }
                "device_swipe" -> {
                    val fromX = request.number("from_x") ?: error("Missing from_x")
                    val fromY = request.number("from_y") ?: error("Missing from_y")
                    val toX = request.number("to_x") ?: error("Missing to_x")
                    val toY = request.number("to_y") ?: error("Missing to_y")
                    val duration = (request.number("duration") ?: 350f).toLong()
                    device.swipe(fromX, fromY, toX, toY, duration).getOrThrow()
                    success("Swiped $fromX,$fromY to $toX,$toY")
                }
                "device_wait" -> {
                    val selector = request.str("selector") ?: error("Missing selector")
                    val timeout = (request.number("timeout") ?: 15_000f).toLong()
                    device.waitFor(selector, timeout).getOrThrow()
                    success("UI matching '$selector' is visible")
                }
                "device_global" -> {
                    val global = request.str("global") ?: error("Missing global action")
                    device.global(global).getOrThrow()
                    success("Performed $global")
                }
                "android_build" -> {
                    val project = projectDir(request)
                    val install = request.str("install")?.toBooleanStrictOrNull() ?: false
                    val log = StringBuilder()
                    val result = apkBuilder.buildAndInstall(project, install) {
                        log.appendLine(it)
                        emitLog(it)
                    }
                    check(result.success) { "Build failed (exit ${result.exitCode})\n${log.takeLast(24_000)}" }
                    success("APK built: ${result.apk?.name}${if (install) "; install session submitted" else ""}")
                }
                "android_project" -> {
                    val layout = AndroidProjectLocator.locate(projectDir(request))
                        ?: error("No Android application found in this workspace")
                    val applicationId = AndroidProjectLocator.applicationId(layout) ?: "(not detected)"
                    success(
                        "gradle-root=${layout.relativeGradleRoot}\n" +
                            "application-module=${layout.modulePath}\n" +
                            "task=${layout.assembleDebugTask}\n" +
                            "wrapper=${if (layout.hasWrapper) "project" else "Orion fallback"}\n" +
                            "application-id=$applicationId",
                    )
                }
                "android_launch" -> {
                    val packageName = request.str("package")
                        ?: AndroidProjectLocator.locate(projectDir(request))?.let(AndroidProjectLocator::applicationId)
                        ?: error("Could not detect applicationId; pass a package")
                    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                        ?: error("$packageName is not installed or has no launcher activity")
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    success("Launched $packageName")
                }
                "godot_launch" -> {
                    check(godotProcess.startForProject(projectDir(request), fullscreen = false)) {
                        "Godot engine failed to start (see logcat tag GodotProcessController)"
                    }
                    // Process-alive only means Godot's Android service exists. The Compose host is
                    // mounted in reaction to that state, and only then does the engine create its
                    // frame loop/control socket. Do not hand control back to Claude in that gap: it
                    // otherwise invents monitor/wakeup loops while repeatedly hitting a dead bridge.
                    var bridgeReady = false
                    for (attempt in 0 until 4) {
                        if (godot.ping()) {
                            bridgeReady = true
                            break
                        }
                        if (attempt < 3) delay(500)
                    }
                    check(bridgeReady) {
                        "Godot process started, but its embedded preview/AI bridge did not become ready within about 22 seconds. Stop and relaunch Godot once."
                    }
                    success("Godot engine started; embedded preview and AI bridge are ready.")
                }
                "godot_stop" -> {
                    check(godotProcess.teardown()) { "Godot engine did not confirm teardown in time" }
                    success("Godot engine stopped.")
                }
                "godot_status" -> {
                    if (!godotProcess.isProcessAlive()) {
                        success("Godot engine is not running. Call godot_launch first.")
                    } else if (godot.ping()) {
                        success("Godot engine is running; bridge responding normally.")
                    } else {
                        failure("Godot process is alive but the bridge did not respond (still booting, or hosting UI backgrounded).")
                    }
                }
                "godot_scene_tree" -> godotOp("scene_tree")
                "godot_capabilities" -> godotOp("capabilities")
                "godot_classdb" -> godotOp(
                    "classdb",
                    buildJsonObject { request.str("class_name")?.let { put("class_name", it) } },
                )
                "godot_save_scene" -> godotOp(
                    "save_scene",
                    buildJsonObject { request.str("path")?.let { put("path", it) } },
                )
                "godot_change_scene" -> godotOp(
                    "change_scene",
                    buildJsonObject { put("path", request.str("path") ?: error("Missing path")) },
                )
                "godot_project_setting" -> godotOp(
                    "project_setting",
                    buildJsonObject {
                        put("name", request.str("name") ?: error("Missing name"))
                        request["value"]?.let { put("value", it) }
                    },
                )
                "godot_input_actions" -> godotOp("input_actions")
                "godot_screenshot" -> {
                    // The bridge always saves to a fixed app-internal path (outside the proot
                    // bind-mount, since the running Godot project is this module's own bundled
                    // default project, not "the current Claude Code project directory" the way
                    // preview_*/device_* screenshots are) — copy it into THIS project's .orion/ dir,
                    // same convention as preview_screenshot/device_screenshot, so Read can reach it.
                    val bridgePath = godot.screenshot() ?: error("Screenshot failed — is godot_launch running?")
                    val out = outputFile(request, "godot.png")
                    File(bridgePath).copyTo(out, overwrite = true)
                    success("Saved ${guestPath(out, request)} (${out.length()} bytes). Use Read on this PNG to inspect the actual running game.")
                }
                "godot_get_property" -> godotOp(
                    "get_property",
                    buildJsonObject {
                        put("path", request.str("path") ?: error("Missing path"))
                        put("property", request.str("property") ?: error("Missing property"))
                    },
                )
                "godot_set_property" -> godotOp(
                    "set_property",
                    buildJsonObject {
                        put("path", request.str("path") ?: error("Missing path"))
                        put("property", request.str("property") ?: error("Missing property"))
                        put("value", request["value"] ?: error("Missing value"))
                    },
                )
                "godot_call_method" -> godotOp(
                    "call_method",
                    buildJsonObject {
                        put("path", request.str("path") ?: error("Missing path"))
                        put("method", request.str("method") ?: error("Missing method"))
                        put("args", request["args"] as? JsonArray ?: JsonArray(emptyList()))
                    },
                )
                "godot_list_properties" -> godotOp(
                    "list_properties",
                    buildJsonObject { put("path", request.str("path") ?: error("Missing path")) },
                )
                "godot_list_methods" -> godotOp(
                    "list_methods",
                    buildJsonObject { put("path", request.str("path") ?: error("Missing path")) },
                )
                "godot_create_node" -> godotOp(
                    "create_node",
                    buildJsonObject {
                        put("parent_path", request.str("parent_path") ?: error("Missing parent_path"))
                        put("class_name", request.str("class_name") ?: error("Missing class_name"))
                        request.str("name")?.let { put("name", it) }
                    },
                )
                "godot_delete_node" -> godotOp(
                    "delete_node",
                    buildJsonObject { put("path", request.str("path") ?: error("Missing path")) },
                )
                "godot_set_paused" -> godotOp(
                    "set_paused",
                    buildJsonObject { put("paused", request.str("paused")?.toBooleanStrictOrNull() ?: error("Missing paused")) },
                )
                "godot_step" -> godotOp(
                    "step",
                    buildJsonObject { put("frames", (request.number("frames") ?: 1f).toInt()) },
                )
                "godot_simulate_input" -> godotOp(
                    "simulate_input",
                    buildJsonObject {
                        put("type", request.str("type") ?: error("Missing type"))
                        request["keycode"]?.let { put("keycode", it) }
                        request["button_index"]?.let { put("button_index", it) }
                        // The command envelope already owns `action` (godot_simulate_input). The
                        // shell shim stores the requested InputMap action separately to prevent it
                        // from overwriting the command before this dispatch point.
                        request.str("input_action")?.let { put("input_action", it) }
                        request["pressed"]?.let { put("pressed", it) }
                        request["position"]?.let { put("position", it) }
                        request["relative"]?.let { put("relative", it) }
                        request["strength"]?.let { put("strength", it) }
                    },
                )
                "godot_reload_script" -> godotOp(
                    "reload_script",
                    buildJsonObject {
                        put("path", request.str("path") ?: error("Missing path"))
                        put("script_path", request.str("script_path") ?: error("Missing script_path"))
                    },
                )
                else -> failure("Unknown action '$action'")
            }
        }.getOrElse { failure(it.message ?: it::class.java.simpleName) }
    }

    /**
     * Shared plumbing for every `godot_*` G4 op: forwards to [GodotController.call] and surfaces the
     * bridge's *raw* response verbatim (including its own `error` message on an op-level failure) —
     * Claude reads exactly what `orion_bridge_server_ops.cpp` returned, same as the local agent's
     * `GodotAgentTools` does via `bridgeResult()`. Distinct from a transport-level failure (bridge
     * unreachable/timeout), which throws and is caught by the surrounding `runCatching` like every
     * other action here.
     */
    private suspend fun godotOp(action: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val response = godot.call(action, params)
            ?: error("Godot bridge unreachable (connection failed or timed out) — is the engine running? Call godot_launch first.")
        return if (response.bool("ok")) success(response.toString()) else failure(response.toString())
    }

    private fun projectDir(request: JsonObject): File {
        val supplied = request.str("project") ?: error("Missing project context")
        val root = File(context.filesDir, "projects").canonicalFile
        val project = File(supplied).canonicalFile
        check(project.path.startsWith(root.path + File.separator)) { "Project is outside Orion storage" }
        check(project.isDirectory) { "Project does not exist" }
        return project
    }

    fun respondToPermission(id: String, decision: ClaudePermissionDecision) {
        permissionWaiters.remove(id)?.complete(decision)
        _pendingPermissions.value = _pendingPermissions.value.filterNot { it.id == id }
    }

    private suspend fun awaitPermission(projectPath: String, data: JsonObject): String {
        val id = UUID.randomUUID().toString()
        val request = ClaudePermissionRequest(
            id = id,
            projectPath = projectPath,
            sessionId = data.str("session_id"),
            toolName = data.str("tool_name") ?: "Tool",
            toolInput = data["tool_input"] as? JsonObject ?: JsonObject(emptyMap()),
            permissionMode = data.str("permission_mode"),
            suggestions = data["permission_suggestions"] as? JsonArray,
        )
        val waiter = CompletableDeferred<ClaudePermissionDecision>()
        permissionWaiters[id] = waiter
        _pendingPermissions.value = _pendingPermissions.value + request
        val decision = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { waiter.await() } ?: ClaudePermissionDecision.DENY
        permissionWaiters.remove(id)
        _pendingPermissions.value = _pendingPermissions.value.filterNot { it.id == id }
        return permissionHookResponse(request, decision)
    }

    private fun permissionHookResponse(
        request: ClaudePermissionRequest,
        decision: ClaudePermissionDecision,
    ): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonObject("hookSpecificOutput") {
                put("hookEventName", "PermissionRequest")
                putJsonObject("decision") {
                    when (decision) {
                        ClaudePermissionDecision.ALLOW_ONCE -> put("behavior", "allow")
                        ClaudePermissionDecision.ALLOW_SESSION -> {
                            put("behavior", "allow")
                            put("updatedPermissions", sessionPermissionUpdates(request))
                        }
                        ClaudePermissionDecision.DENY -> {
                            put("behavior", "deny")
                            put("message", "The user did not allow this action in Orion.")
                            put("interrupt", false)
                        }
                    }
                }
            }
        },
    )

    private fun sessionPermissionUpdates(request: ClaudePermissionRequest): JsonArray {
        val suggestions = request.suggestions
        if (suggestions != null && suggestions.isNotEmpty()) {
            return JsonArray(suggestions.map { element ->
                val suggestion = element as? JsonObject ?: return@map element
                JsonObject(suggestion + ("destination" to JsonPrimitive("session")))
            })
        }
        return buildJsonArray {
            add(buildJsonObject {
                put("type", "addRules")
                put("behavior", "allow")
                put("destination", "session")
                putJsonArray("rules") {
                    add(buildJsonObject { put("toolName", request.toolName) })
                }
            })
        }
    }

    private fun parseTelemetry(projectPath: String, data: JsonObject): ClaudeSessionTelemetry {
        val contextWindow = data["context_window"] as? JsonObject
        val model = data["model"] as? JsonObject
        val cost = data["cost"] as? JsonObject
        val rateLimits = data["rate_limits"] as? JsonObject
        val fiveHour = rateLimits?.get("five_hour") as? JsonObject
        val sevenDay = rateLimits?.get("seven_day") as? JsonObject
        val input = contextWindow?.long("total_input_tokens") ?: 0L
        val output = contextWindow?.long("total_output_tokens") ?: 0L
        return ClaudeSessionTelemetry(
            projectPath = projectPath,
            sessionId = data.str("session_id"),
            modelName = model?.str("display_name") ?: model?.str("id"),
            contextUsedTokens = input + output,
            contextWindowTokens = contextWindow?.long("context_window_size") ?: 0L,
            contextUsedPercentage = contextWindow?.double("used_percentage"),
            contextRemainingPercentage = contextWindow?.double("remaining_percentage"),
            costUsd = cost?.double("total_cost_usd"),
            durationMs = cost?.long("total_duration_ms"),
            fiveHour = ClaudeRateLimitTelemetry(fiveHour?.double("used_percentage"), fiveHour?.long("resets_at")),
            sevenDay = ClaudeRateLimitTelemetry(sevenDay?.double("used_percentage"), sevenDay?.long("resets_at")),
        )
    }

    private fun outputFile(request: JsonObject, fallback: String): File {
        val project = projectDir(request)
        val relative = request.str("output")?.ifBlank { null } ?: ".orion/$fallback"
        val output = File(project, relative).canonicalFile
        check(output.path.startsWith(project.path + File.separator)) { "Output escapes the project" }
        check(output.extension.equals("png", ignoreCase = true)) { "Screenshot output must be a PNG" }
        return output
    }

    private fun guestPath(file: File, request: JsonObject): String =
        "/root/project/" + file.relativeTo(projectDir(request)).invariantSeparatorsPath

    private fun success(output: String): JsonObject = buildJsonObject { put("ok", true); put("output", output) }
    private fun failure(output: String): JsonObject = buildJsonObject { put("ok", false); put("output", output) }
    private fun progress(output: String): JsonObject = buildJsonObject { put("event", "log"); put("output", output) }
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"

    companion object {
        private const val MAX_REQUEST_CHARS = 128_000
        private const val PERMISSION_TIMEOUT_MS = 15 * 60 * 1000L
    }
}
