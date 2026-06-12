package com.legion.orion.agent

import com.legion.orion.build.ApkBuildController
import com.legion.orion.data.project.ScaffoldManager
import com.legion.orion.engine.agent.AgentTool
import com.legion.orion.engine.agent.ToolContext
import com.legion.orion.engine.agent.ToolResult
import com.legion.orion.engine.agent.tools.Param
import com.legion.orion.engine.agent.tools.objectSchema
import com.legion.orion.feature.preview.PreviewController
import com.legion.orion.runtime.DevServerState
import com.legion.orion.runtime.NodeController
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tools that need app-level capabilities (scaffolds, the Node runtime, the dev server). The
 * pure file/search tools live in engine:agent; these are appended to the registry at runtime.
 */
@Singleton
class AppAgentTools @Inject constructor(
    private val scaffolds: ScaffoldManager,
    private val node: NodeController,
    private val devServerState: DevServerState,
    private val preview: PreviewController,
    private val apkBuilder: ApkBuildController,
    private val buildOffload: com.legion.orion.p2p.BuildOffloadManager,
    private val p2p: com.legion.orion.infra.p2p.P2pController,
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
        InstallBuildToolchainTool(apkBuilder),
        BuildApkTool(apkBuilder),
        RunBuildJobTool(buildOffload, p2p),
        CheckBuildJobTool(buildOffload),
    )
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
        val tree = preview.accessibilityTree().orEmpty()
        val problems = buildList {
            if (diag.consoleErrors.isNotEmpty()) add("console errors:\n  " + diag.consoleErrors.joinToString("\n  "))
            if (diag.failedRequests.isNotEmpty()) add("failed requests:\n  " + diag.failedRequests.joinToString("\n  "))
            if (tree.isBlank()) add("page has no accessible content")
        }
        return if (problems.isEmpty()) ToolResult("QA gate PASSED: no console errors, no failed requests, page has content.")
        else ToolResult("QA gate FAILED:\n" + problems.joinToString("\n"), isError = true)
    }
}
