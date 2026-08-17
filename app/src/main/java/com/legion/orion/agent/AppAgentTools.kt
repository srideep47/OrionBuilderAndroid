package com.legion.orion.agent

import android.content.Context
import android.content.Intent
import com.legion.orion.automation.DeviceAutomationController
import com.legion.orion.build.ApkBuildController
import com.legion.orion.data.project.ScaffoldManager
import com.legion.orion.engine.agent.AgentTool
import com.legion.orion.engine.agent.ToolContext
import com.legion.orion.engine.agent.ToolResult
import com.legion.orion.engine.agent.tools.Param
import com.legion.orion.engine.agent.tools.objectSchema
import com.legion.orion.feature.preview.PreviewController
import com.legion.orion.internet.DevIdentityStore
import com.legion.orion.internet.InternetAgentTools
import com.legion.orion.internet.InternetManager
import com.legion.orion.runtime.DevServerState
import com.legion.orion.runtime.NodeController
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tools that need app-level capabilities (scaffolds, the Node runtime, the dev server,
 * Internet Manager). The pure file/search tools live in engine:agent; these are appended to the
 * registry at runtime.
 */
@Singleton
class AppAgentTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scaffolds: ScaffoldManager,
    private val node: NodeController,
    private val devServerState: DevServerState,
    private val preview: PreviewController,
    private val apkBuilder: ApkBuildController,
    private val buildOffload: com.legion.orion.p2p.BuildOffloadManager,
    private val p2p: com.legion.orion.infra.p2p.P2pController,
    private val media: com.legion.orion.data.media.MediaManager,
    private val device: DeviceAutomationController,
    private val internet: InternetManager,
    private val devIdentities: DevIdentityStore,
) {
    fun list(): List<AgentTool> = listOf(
        CreateProjectTool(scaffolds),
        DetectStackTool(),
        RunTerminalCommandTool(node),
        StartDevServerTool(node, devServerState),
        StopDevServerTool(node, devServerState),
        RunTypeChecksTool(node),
        TakeScreenshotTool(preview),
        GetAccessibilityTreeTool(preview),
        BrowserQaGateTool(preview),
        TakeDeviceScreenshotTool(device),
        GetDeviceUiTreeTool(device),
        DeviceUiActionTool(device),
        CheckRenderTool(preview),
        EvalPreviewTool(preview),
        ReadConsoleLogsTool(preview),
        GetPageHtmlTool(preview),
        InstallDependencyTool(node),
        InstallBuildToolchainTool(apkBuilder),
        BuildApkTool(apkBuilder),
        LaunchAndroidAppTool(context),
        RunBuildJobTool(buildOffload, p2p),
        CheckBuildJobTool(buildOffload),
        QueueMediaJobTool(media),
    ) + InternetAgentTools.list(internet, devIdentities)
}

/** Captures the currently visible Android app after the user enables Orion device verification. */
private class TakeDeviceScreenshotTool(private val device: DeviceAutomationController) : AgentTool {
    override val name = "take_device_screenshot"
    override val description =
        "Capture the actual Android device screen, including an installed app launched for testing. " +
            "Requires the user-enabled Orion device verification Accessibility service."
    override val parameters = objectSchema(
        Param("output", "string", "Project-relative PNG path (default .orion/device.png)", required = false),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val relative = args.str("output")?.ifBlank { null } ?: ".orion/device.png"
        val out = runCatching { File(ctx.projectDir, relative).canonicalFile }.getOrNull()
            ?: return ToolResult("Invalid output path", isError = true)
        if (!out.path.startsWith(ctx.projectDir.canonicalPath + File.separator) || !out.extension.equals("png", true)) {
            return ToolResult("Output must be a PNG inside the project", isError = true)
        }
        return device.screenshot(out)
            .fold(
                onSuccess = { ToolResult("Device screenshot saved to ${it.relativeTo(ctx.projectDir).invariantSeparatorsPath} (${it.length()} bytes)") },
                onFailure = { ToolResult(it.message ?: "Device screenshot failed", isError = true) },
            )
    }
}

private class GetDeviceUiTreeTool(private val device: DeviceAutomationController) : AgentTool {
    override val name = "get_device_ui_tree"
    override val description =
        "Read the labelled accessibility tree of the currently visible Android app, including text, " +
            "view ids, actions and screen bounds. Password values are always redacted."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        device.uiTree().fold(::ToolResult) { ToolResult(it.message ?: "UI tree failed", isError = true) }
}

private class DeviceUiActionTool(private val device: DeviceAutomationController) : AgentTool {
    override val name = "interact_with_device"
    override val description =
        "Interact with the currently visible Android app for QA. Actions: click (selector), type " +
            "(selector+text), tap (x+y), or back/home/recents/notifications/quick-settings."
    override val parameters = objectSchema(
        Param("action", "string", "click | type | tap | back | home | recents | notifications | quick-settings"),
        Param("selector", "string", "Visible text, content description, or full view id", required = false),
        Param("text", "string", "Text for the type action", required = false),
        Param("x", "number", "Screen x coordinate for tap", required = false),
        Param("y", "number", "Screen y coordinate for tap", required = false),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val action = args.str("action")?.lowercase() ?: return ToolResult("Missing action", isError = true)
        val result = when (action) {
            "click" -> device.click(args.str("selector") ?: return ToolResult("click needs selector", true))
            "type" -> device.type(
                args.str("selector") ?: return ToolResult("type needs selector", true),
                args.str("text") ?: return ToolResult("type needs text", true),
            )
            "tap" -> device.tap(
                args["x"]?.jsonPrimitive?.floatOrNull ?: return ToolResult("tap needs x", true),
                args["y"]?.jsonPrimitive?.floatOrNull ?: return ToolResult("tap needs y", true),
            )
            else -> device.global(action)
        }
        return result.fold(
            onSuccess = { ToolResult("Device action '$action' completed") },
            onFailure = { ToolResult(it.message ?: "Device action failed", isError = true) },
        )
    }
}

private class LaunchAndroidAppTool(private val context: Context) : AgentTool {
    override val name = "launch_android_app"
    override val description = "Launch an installed Android app for visual/device verification. Detects applicationId from the current Gradle app module when omitted."
    override val parameters = objectSchema(
        Param("package", "string", "Installed application package; omit to detect from app/build.gradle(.kts)", required = false),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val packageName = args.str("package") ?: detectAppId(ctx.projectDir)
            ?: return ToolResult("Could not detect applicationId; pass package explicitly", isError = true)
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolResult("$packageName is not installed or has no launcher activity", isError = true)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult("Launched $packageName")
    }

    private fun detectAppId(project: File): String? {
        val file = listOf(File(project, "app/build.gradle.kts"), File(project, "app/build.gradle")).firstOrNull(File::isFile)
        val text = file?.let { runCatching { it.readText() }.getOrDefault("") }.orEmpty()
        return Regex("applicationId\\s*(?:=\\s*)?[\\\"']([^\\\"']+)[\\\"']").find(text)?.groupValues?.getOrNull(1)
            ?: Regex("namespace\\s*(?:=\\s*)?[\\\"']([^\\\"']+)[\\\"']").find(text)?.groupValues?.getOrNull(1)
    }
}

/** Queues an image/video generation job to a P2P peer — NEVER generated locally. */
private class QueueMediaJobTool(private val media: com.legion.orion.data.media.MediaManager) : AgentTool {
    override val name = "queue_media_job"
    override val description =
        "Queue an image or video generation job to a connected compute peer. Generation never runs on this device. " +
            "Returns a job id; the result lands in the Media gallery when the peer finishes."
    override val parameters = objectSchema(
        Param("type", "string", "Media type: image | video"),
        Param("prompt", "string", "The generation prompt"),
        Param("params", "string", "Optional JSON object of extra params (size, steps, seed…)", required = false),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val type = args.str("type")?.lowercase() ?: return ToolResult("Missing 'type'", isError = true)
        if (type != "image" && type != "video") return ToolResult("type must be 'image' or 'video'", isError = true)
        val prompt = args.str("prompt") ?: return ToolResult("Missing 'prompt'", isError = true)
        val params = args.str("params")
        return runCatching {
            val jobId = media.queue(type, prompt, params)
            val note = if (media.hasComputePeer()) "submitted to a compute peer" else "queued (waiting for a compute peer)"
            ToolResult("Media job $jobId $note. Track progress in the Media tab.")
        }.getOrElse { ToolResult("Failed to queue media job: ${it.message}", isError = true) }
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** Scaffolds a template into the current (empty) project directory. */
private class CreateProjectTool(private val scaffolds: ScaffoldManager) : AgentTool {
    override val name = "create_project"
    override val description = "Scaffold a project template into the current project (templates: vite-react-ts, static-web, android-kt)."
    override val parameters = objectSchema(
        Param("template", "string", "Template id: vite-react-ts | static-web | android-kt"),
    )
    override val mutating = true

    private val templates get() = scaffolds.templates.joinToString(", ")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val template = args.str("template") ?: return ToolResult("Missing 'template'", isError = true)
        if (template !in scaffolds.templates) {
            return ToolResult("Unknown template '$template'. Available: $templates", isError = true)
        }
        return runCatching {
            scaffolds.scaffold(template, ctx.projectDir)
            ToolResult("Scaffolded '$template' into project.")
        }.getOrElse { ToolResult("Scaffold failed: ${it.message}", isError = true) }
    }
}

/** Inspects the project to report its stack. */
private class DetectStackTool : AgentTool {
    override val name = "detect_project_stack"
    override val description = "Detect the project's stack (framework, package manager, entry points)."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val dir = ctx.projectDir
        val pkg = File(dir, "package.json")
        val findings = StringBuilder()
        if (pkg.isFile) {
            val text = pkg.readText()
            findings.append("node project (package.json present)\n")
            listOf("react", "vue", "svelte", "next", "vite", "express").forEach { dep ->
                if (text.contains("\"$dep\"")) findings.append("- uses $dep\n")
            }
        }
        if (File(dir, "index.html").isFile) findings.append("static index.html present\n")
        if (File(dir, "build.gradle.kts").isFile || File(dir, "build.gradle").isFile) findings.append("gradle project\n")
        return ToolResult(if (findings.isEmpty()) "empty / unknown project" else findings.toString().trim())
    }
}

/** Runs npm / node -e in the embedded Node runtime. */
private class RunTerminalCommandTool(private val node: NodeController) : AgentTool {
    override val name = "run_terminal_command"
    override val description =
        "Run a command in the project. Supports 'npm ...' and 'node -e ...' (run in the embedded " +
            "Node runtime). Heavy desktop builds are not available on-device."
    override val parameters = objectSchema(Param("command", "string", "Full command line, e.g. 'npm install'"))
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = args.str("command") ?: return ToolResult("Missing 'command'", isError = true)
        val argv = command.trim().split(Regex("\\s+"))
        if (argv.firstOrNull() !in setOf("npm", "node", "npx")) {
            return ToolResult(
                "Only npm/node/npx run on-device. For other commands, offload to a peer (P2P, later phase).",
                isError = true,
            )
        }
        val (code, output) = node.exec(argv, ctx.projectDir.absolutePath)
        return ToolResult("exit=$code\n${output.take(8000)}", isError = code != 0)
    }
}

/** Starts the dev server for the project and records it for the preview screen. */
private class StartDevServerTool(
    private val node: NodeController,
    private val devServerState: DevServerState,
) : AgentTool {
    override val name = "start_dev_server"
    override val description = "Start the dev server for the project. Returns the localhost URL the preview opens."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val port = node.startDevServer(ctx.projectDir.absolutePath)
        if (port <= 0) return ToolResult("Failed to start dev server", isError = true)
        devServerState.set(DevServerState.Running(port, ctx.projectDir.absolutePath))
        return ToolResult("Dev server running at http://127.0.0.1:$port/ — open the Preview tab.")
    }
}

private class StopDevServerTool(
    private val node: NodeController,
    private val devServerState: DevServerState,
) : AgentTool {
    override val name = "stop_dev_server"
    override val description = "Stop the running dev server."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val running = devServerState.current.value ?: return ToolResult("No dev server running.")
        node.stopDevServer(running.port)
        devServerState.set(null)
        return ToolResult("Dev server stopped.")
    }
}

/** Type-checks a TypeScript project via the in-process tsc (requires npm install first). */
private class RunTypeChecksTool(private val node: NodeController) : AgentTool {
    override val name = "run_type_checks"
    override val description = "Run TypeScript type checks (tsc --noEmit) on the project."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val (code, output) = node.exec(listOf("npx", "tsc", "--noEmit"), ctx.projectDir.absolutePath)
        return ToolResult("exit=$code\n${output.take(8000)}", isError = code != 0)
    }
}

/** Captures a PNG of the live preview into the project's .orion/ dir. */
private class TakeScreenshotTool(private val preview: PreviewController) : AgentTool {
    override val name = "take_screenshot"
    override val description = "Capture a screenshot of the live preview (open the dev server first)."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!preview.isAttached) return ToolResult("Preview is not open. Start the dev server and open Preview.", isError = true)
        val out = File(ctx.projectDir, ".orion/screenshot.png")
        return if (preview.screenshot(out)) ToolResult("Screenshot saved to .orion/screenshot.png (${out.length()} bytes)")
        else ToolResult("Screenshot failed (preview not rendered yet)", isError = true)
    }
}

/** Returns a compact accessibility outline of the rendered page. */
private class GetAccessibilityTreeTool(private val preview: PreviewController) : AgentTool {
    override val name = "get_accessibility_tree"
    override val description = "Get a compact accessibility outline (roles + labels) of the rendered preview."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!preview.isAttached) return ToolResult("Preview is not open.", isError = true)
        val tree = preview.accessibilityTree()
        return if (tree.isNullOrBlank()) ToolResult("(empty page / no accessible elements)")
        else ToolResult(tree)
    }
}

/** Provisions the on-device Android build toolchain (SDK + build-tools + platform-tools, optional JDK). */
private class InstallBuildToolchainTool(private val builder: ApkBuildController) : AgentTool {
    override val name = "install_build_toolchain"
    override val description =
        "Install the on-device Android build toolchain (Android SDK, build-tools, JDK 17) from the " +
            "archives bundled in the app. Needed once before build_apk (extracts ~500MB, offline). " +
            "source_dir/jdk_url override the source for advanced cases."
    override val parameters = objectSchema(
        Param("source_dir", "string", "Dir holding pushed *.tar.xz toolchain archives (optional)", required = false),
        Param("jdk_url", "string", "URL of a standalone bionic arm64 OpenJDK 17 .tar.xz (optional)", required = false),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val sourceDir = args.str("source_dir")
        val jdkUrl = args.str("jdk_url")
        val log = StringBuilder()
        val status = builder.installToolchain(sourceDir, jdkUrl) { line -> log.appendLine(line) }
        val tail = log.toString().lines().takeLast(40).joinToString("\n")
        return ToolResult("${status.describe()}\n$tail", isError = !status.provisioned)
    }
}

/** Builds the current Android (Gradle) project to a debug APK on-device and installs it. */
private class BuildApkTool(private val builder: ApkBuildController) : AgentTool {
    override val name = "build_apk"
    override val description =
        "Build the current Android Gradle project to a signed debug APK on-device and install it " +
            "(PackageInstaller). Scaffold 'android-kt' first. Heavy: the model is unloaded during the build."
    override val parameters = objectSchema(
        Param("install", "boolean", "Install the APK after building (default true)", required = false),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val dir = ctx.projectDir
        val isGradle = File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile
        if (!isGradle) return ToolResult("Not a Gradle project (no settings.gradle). Scaffold 'android-kt' first.", isError = true)

        val status = builder.toolchainStatus()
        if (!status.provisioned) return ToolResult(status.describe(), isError = true)

        val install = args["install"]?.jsonPrimitive?.booleanOrNull ?: true
        val log = StringBuilder()
        val result = builder.buildAndInstall(dir, install) { line -> log.appendLine(line) }
        val tail = log.toString().lines().takeLast(60).joinToString("\n")
        return if (result.success) ToolResult("APK built: ${result.apk?.name}\n$tail")
        else ToolResult("Build failed (exit=${result.exitCode}).\n$tail", isError = true)
    }
}

/** Offloads a native desktop build (win/linux/mac) to a trusted peer over P2P. */
private class RunBuildJobTool(
    private val offload: com.legion.orion.p2p.BuildOffloadManager,
    private val p2p: com.legion.orion.infra.p2p.P2pController,
) : AgentTool {
    override val name = "run_build_job"
    override val description =
        "Offload a native desktop build (win | linux | mac) of this project to a trusted online " +
            "peer over the Orion network. Returns a job id; poll with check_build_job. Phones " +
            "cannot run desktop toolchains locally."
    override val parameters = objectSchema(
        Param("target", "string", "Build target: win | linux | mac"),
        Param("peer", "string", "Public key (or prefix) of the peer to use; omit to auto-pick", required = false),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val target = args.str("target")?.lowercase() ?: return ToolResult("Missing 'target'", isError = true)
        if (target !in setOf("win", "linux", "mac")) {
            return ToolResult("Invalid target '$target' (win | linux | mac)", isError = true)
        }
        val peers = p2p.peers.value.filter { it.isTrusted && it.status == "online" }
        val wanted = args.str("peer")
        val peer = when {
            wanted != null -> peers.firstOrNull { it.publicKey.startsWith(wanted, ignoreCase = true) }
                ?: return ToolResult("Peer '$wanted' is not a connected trusted peer", isError = true)
            else -> peers.firstOrNull { it.deviceType in setOf("desktop", "laptop") }
                ?: return ToolResult(
                    "No trusted desktop peer is online. Go online on the Network tab and befriend a desktop running Orion Builder.",
                    isError = true,
                )
        }
        return runCatching {
            val jobId = offload.submit(ctx.projectDir.name, ctx.projectDir, target, peer.publicKey)
            ToolResult("Build job $jobId submitted to ${peer.displayName} ($target). Poll with check_build_job.")
        }.getOrElse { ToolResult("Submit failed: ${it.message}", isError = true) }
    }
}

/** Polls a previously submitted offloaded build job. */
private class CheckBuildJobTool(
    private val offload: com.legion.orion.p2p.BuildOffloadManager,
) : AgentTool {
    override val name = "check_build_job"
    override val description = "Check the status and recent log of an offloaded build job (from run_build_job)."
    override val parameters = objectSchema(Param("job_id", "string", "The job id returned by run_build_job"))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val jobId = args.str("job_id") ?: return ToolResult("Missing 'job_id'", isError = true)
        val job = offload.job(jobId) ?: return ToolResult("Unknown job '$jobId'", isError = true)
        val log = job.logTail?.takeLast(2000) ?: ""
        val artifact = job.artifactPath?.let { "\nartifact: $it" } ?: ""
        return ToolResult("state=${job.state} target=${job.target}$artifact\n$log", isError = job.state == "failed")
    }
}

/**
 * Verifies the page is actually STYLED — the check the QA gate can't make from
 * console/network alone. Catches the common failure where a stylesheet is missing,
 * mis-linked, or served with the wrong content-type, leaving a blank/unstyled page.
 */
private class CheckRenderTool(private val preview: PreviewController) : AgentTool {
    override val name = "check_render"
    override val description =
        "Inspect the LIVE preview and report whether CSS actually applied: stylesheet load state, " +
            "rule counts, and the computed body background/font. Call after start_dev_server to confirm " +
            "a web page is styled (not just that files exist). Fails if the page is rendering unstyled."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!preview.isAttached) {
            return ToolResult("Preview is not open. Start the dev server and open the Preview tab first.", isError = true)
        }
        val report = preview.renderReport()
            ?: return ToolResult("Could not read the preview (page not loaded yet). Reload and retry.", isError = true)
        val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(report) as? JsonObject }.getOrNull()
        if (obj != null) {
            val cssRules = obj["cssRules"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val links = obj["links"]?.let { runCatching { (it as kotlinx.serialization.json.JsonArray) }.getOrNull() }
            val styleTags = obj["styleTags"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val brokenLinks = links?.filter {
                (it as? JsonObject)?.get("loaded")?.jsonPrimitive?.contentOrNull == "false"
            }.orEmpty()
            val problems = buildList {
                brokenLinks.forEach { l ->
                    val href = (l as? JsonObject)?.get("href")?.jsonPrimitive?.contentOrNull
                    add("stylesheet '$href' did NOT load/apply — check the path is correct and the file exists")
                }
                if (cssRules == 0 && (links != null && links.isNotEmpty() || styleTags > 0)) {
                    add("0 CSS rules are applied even though stylesheets/style tags are present — the page is rendering UNSTYLED. Verify <link href> paths and that style.css has content.")
                }
                if (cssRules == 0 && (links == null || links.isEmpty()) && styleTags == 0) {
                    add("the page has NO stylesheets at all — add a <link rel=\"stylesheet\"> (or a <style> block) so it isn't unstyled.")
                }
            }
            return if (problems.isEmpty()) {
                ToolResult("Render OK — styles applied. $report")
            } else {
                ToolResult("Render PROBLEMS:\n- " + problems.joinToString("\n- ") + "\n\nfull report: $report", isError = true)
            }
        }
        return ToolResult(report)
    }
}

/** Runs arbitrary JS in the preview — powerful inspection / interaction. */
private class EvalPreviewTool(private val preview: PreviewController) : AgentTool {
    override val name = "eval_in_preview"
    override val description =
        "Evaluate a JavaScript expression in the live preview and return its result (stringified). " +
            "Use to inspect the rendered DOM/state or trigger interactions, e.g. " +
            "\"getComputedStyle(document.querySelector('.btn')).backgroundColor\" or " +
            "\"document.querySelectorAll('.card').length\"."
    override val parameters = objectSchema(Param("expression", "string", "JavaScript expression to evaluate"))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!preview.isAttached) return ToolResult("Preview is not open.", isError = true)
        val expr = args.str("expression") ?: return ToolResult("Missing 'expression'", isError = true)
        // Wrap so a bare expression returns a string; errors are reported, not thrown.
        val js = "(function(){try{return String(eval(${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(expr))}));}catch(e){return 'ERROR: '+e;}})();"
        val out = preview.eval(js) ?: return ToolResult("(no result / preview not ready)")
        return ToolResult(out.take(4000), isError = out.startsWith("ERROR:"))
    }
}

/** Returns all captured console output (not just errors). */
private class ReadConsoleLogsTool(private val preview: PreviewController) : AgentTool {
    override val name = "read_console_logs"
    override val description = "Read recent browser console output from the preview (all levels), newest last."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val logs = preview.consoleLog()
        if (logs.isEmpty()) return ToolResult("(no console output captured)")
        val text = logs.takeLast(80).joinToString("\n") {
            "[${it.level}] ${it.message}${if (it.source.isNotBlank()) " (${it.source}:${it.line})" else ""}"
        }
        return ToolResult(text)
    }
}

/** Returns the rendered DOM HTML — useful to confirm structure actually mounted. */
private class GetPageHtmlTool(private val preview: PreviewController) : AgentTool {
    override val name = "get_page_html"
    override val description =
        "Get the rendered HTML of the live preview (document.documentElement.outerHTML). Confirms what " +
            "actually mounted in the DOM, including dynamically-rendered content."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!preview.isAttached) return ToolResult("Preview is not open.", isError = true)
        val html = preview.eval("document.documentElement.outerHTML")
            ?: return ToolResult("(page not loaded)", isError = true)
        return ToolResult(html.take(12_000))
    }
}

/** Installs an npm dependency into the project (allowlisted to npm). */
private class InstallDependencyTool(private val node: NodeController) : AgentTool {
    override val name = "install_dependency"
    override val description =
        "Install one or more npm packages into the project (runs 'npm install <pkgs>'). " +
            "Use for web projects that need a library. Dev deps: set dev=true."
    override val parameters = objectSchema(
        Param("packages", "string", "Space-separated package names, e.g. 'zustand clsx'"),
        Param("dev", "boolean", "Install as devDependencies (default false)", required = false),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val pkgs = args.str("packages")?.trim().orEmpty()
        if (pkgs.isEmpty()) return ToolResult("Missing 'packages'", isError = true)
        // Block obviously unsafe shell metacharacters — only package tokens allowed.
        if (pkgs.any { it in ";|&`$<>(){}\n" }) return ToolResult("Invalid characters in package list", isError = true)
        val dev = args["dev"]?.jsonPrimitive?.booleanOrNull ?: false
        val argv = buildList {
            add("npm"); add("install"); if (dev) add("--save-dev")
            addAll(pkgs.split(Regex("\\s+")).filter { it.isNotBlank() })
        }
        val (code, output) = node.exec(argv, ctx.projectDir.absolutePath)
        return ToolResult("exit=$code\n${output.take(6000)}", isError = code != 0)
    }
}

/** Gate that must pass before claiming a UI task is done: no console errors, no failed requests, page has content. */
private class BrowserQaGateTool(private val preview: PreviewController) : AgentTool {
    override val name = "browser_qa_gate"
    override val description =
        "Verify the preview is healthy: no console errors, no failed network requests, and the page " +
            "has accessible content. Call before claiming a UI task is complete."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!preview.isAttached) return ToolResult("Preview is not open — cannot run QA gate.", isError = true)
        val diag = preview.diagnostics()
        val failedRequests = diag.failedRequests.filterNot { it.contains("/favicon.ico", ignoreCase = true) }
        val tree = preview.accessibilityTree().orEmpty()
        val report = preview.renderReport()
        val obj = report?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val cssRules = obj?.get("cssRules")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
        val links = (obj?.get("links") as? kotlinx.serialization.json.JsonArray)
        val styleTags = obj?.get("styleTags")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val problems = buildList {
            if (diag.consoleErrors.isNotEmpty()) add("console errors:\n  " + diag.consoleErrors.joinToString("\n  "))
            if (failedRequests.isNotEmpty()) add("failed requests:\n  " + failedRequests.joinToString("\n  "))
            if (tree.isBlank()) add("page has no accessible content")
            // Unstyled-page detection (the silent failure the other checks miss).
            if (cssRules == 0 && ((links != null && links.isNotEmpty()) || styleTags > 0)) {
                add("page is UNSTYLED — stylesheets are referenced but 0 CSS rules applied (check <link href> paths / file contents)")
            }
            links?.forEach { l ->
                if ((l as? JsonObject)?.get("loaded")?.jsonPrimitive?.contentOrNull == "false") {
                    val href = (l as? JsonObject)?.get("href")?.jsonPrimitive?.contentOrNull
                    add("stylesheet '$href' failed to apply")
                }
            }
        }
        return if (problems.isEmpty()) {
            ToolResult("QA gate PASSED: no console errors, no failed requests, page has content, styles applied (cssRules=$cssRules).")
        } else {
            ToolResult("QA gate FAILED:\n" + problems.joinToString("\n"), isError = true)
        }
    }
}
