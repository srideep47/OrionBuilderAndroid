package com.legion.orion.build

import android.content.Context
import com.legion.orion.claudecode.ClaudeCodeToolchain
import com.legion.orion.inference.InferenceController
import com.legion.orion.runtime.buildtools.ApkInstaller
import com.legion.orion.runtime.buildtools.BuildResult
import com.legion.orion.runtime.buildtools.BuildToolchain
import com.legion.orion.runtime.buildtools.GradleBuildRunner
import com.legion.orion.runtime.buildtools.ToolchainInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives an on-device APK build end to end: provisioning check → auto-unload the LLM (Gradle and the
 * model cannot both fit in RAM) → Gradle `assembleDebug` → optional PackageInstaller install →
 * reload the LLM. This is the app-side memory orchestrator for builds; the toolchain plumbing lives
 * in :runtime:buildtools.
 */
@Singleton
class ApkBuildController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inference: InferenceController,
) {
    private val toolchain = BuildToolchain(context)
    private val runner = GradleBuildRunner(toolchain)
    private val claudeToolchain = ClaudeCodeToolchain(context)
    private val prootRunner = ProotAndroidBuildRunner(claudeToolchain)
    private val installer = ApkInstaller(context)
    private val toolchainInstaller = ToolchainInstaller(toolchain, context.assets)

    fun toolchainStatus(): BuildToolchain.Status {
        val native = toolchain.status()
        return if (native.provisioned || !prootRunner.status().provisioned) native
        else BuildToolchain.Status(provisioned = true, missing = emptyList())
    }

    /**
     * Installs the on-device toolchain (Android SDK, build-tools, JDK 17) by extracting it into
     * filesDir/toolchain. Default source is the **archives bundled in the APK** (fully self-contained,
     * offline). [sourceDir] installs from pushed `.tar.xz` instead; [jdkUrl] uses the HTTP SDK path
     * plus a downloaded JDK. Idempotent — already-installed components are skipped.
     */
    suspend fun installToolchain(
        sourceDir: String?,
        jdkUrl: String?,
        onProgress: (String) -> Unit,
    ): BuildToolchain.Status {
        val components = when {
            !sourceDir.isNullOrBlank() -> ToolchainInstaller.componentsFromDir(File(sourceDir))
            !jdkUrl.isNullOrBlank() -> ToolchainInstaller.withJdk(jdkUrl)
            else -> ToolchainInstaller.assetComponents()  // self-contained default
        }
        if (components.isEmpty()) {
            onProgress("No toolchain archives found in ${sourceDir ?: "(none)"}")
            return toolchain.status()
        }
        return toolchainInstaller.install(components, onProgress)
    }

    suspend fun buildAndInstall(
        projectDir: File,
        install: Boolean,
        onLog: (String) -> Unit,
    ): BuildResult {
        val nativeStatus = toolchain.status()
        val prootStatus = prootRunner.status()
        if (!nativeStatus.provisioned && !prootStatus.provisioned) {
            return BuildResult(
                false,
                null,
                -1,
                "No Android build toolchain is ready. Native: ${nativeStatus.missing.joinToString()}; " +
                    "Claude runtime: ${prootStatus.missing.joinToString()}",
            )
        }
        val layout = AndroidProjectLocator.locate(projectDir)
            ?: return BuildResult(
                false,
                null,
                -1,
                "No Android application was found. Orion searched the project and nested Gradle roots up to five levels deep.",
            )
        val workId = "build:${layout.workspaceRoot.canonicalPath}"
        DevelopmentWorkService.acquire(context, workId, "Building ${layout.workspaceRoot.name}")

        // Free the model for the build, remembering what to restore afterward.
        val restore = inference.state.value.takeIf { inference.isLoaded && it.modelDir != null && it.backend != null }
        if (restore != null) {
            onLog("Unloading model to free memory for the build…")
            runCatching { inference.unload() }
        }
        try {
            val result = if (nativeStatus.provisioned) {
                onLog("Using Orion's native Android toolchain.")
                onLog(
                    "Detected Gradle root '${layout.relativeGradleRoot}', task ${layout.assembleDebugTask}, " +
                        if (layout.hasWrapper) "using the project's wrapper." else "but it has no wrapper.",
                )
                runner.assembleDebugApk(layout.gradleRoot, layout.assembleDebugTask, layout.applicationModule, onLog)
            } else {
                prootRunner.assembleDebugApk(layout, onLog)
            }
            val apk = result.apk
            if (result.success && install && apk != null) {
                onLog("Installing ${apk.name} via PackageInstaller…")
                installer.install(apk)
                    .onSuccess { onLog("Install session committed — confirm the system prompt.") }
                    .onFailure { onLog("Install failed: ${it.message}") }
            }
            return result
        } finally {
            DevelopmentWorkService.release(context, workId)
            if (restore != null) {
                onLog("Reloading model…")
                runCatching { inference.load(restore.modelDir!!, restore.backend!!) }
            }
        }
    }
}
