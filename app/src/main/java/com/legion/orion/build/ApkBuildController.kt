package com.legion.orion.build

import android.content.Context
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
    @ApplicationContext context: Context,
    private val inference: InferenceController,
) {
    private val toolchain = BuildToolchain(context)
    private val runner = GradleBuildRunner(toolchain)
    private val installer = ApkInstaller(context)
    private val toolchainInstaller = ToolchainInstaller(toolchain, context.assets)

    fun toolchainStatus(): BuildToolchain.Status = toolchain.status()

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
        val status = toolchain.status()
        if (!status.provisioned) return BuildResult(false, null, -1, status.describe())

        // Free the model for the build, remembering what to restore afterward.
        val restore = inference.state.value.takeIf { inference.isLoaded && it.modelDir != null && it.backend != null }
        if (restore != null) {
            onLog("Unloading model to free memory for the build…")
            runCatching { inference.unload() }
        }
        try {
            val result = runner.assembleDebugApk(projectDir, onLog)
            val apk = result.apk
            if (result.success && install && apk != null) {
                onLog("Installing ${apk.name} via PackageInstaller…")
                installer.install(apk)
                    .onSuccess { onLog("Install session committed — confirm the system prompt.") }
                    .onFailure { onLog("Install failed: ${it.message}") }
            }
            return result
        } finally {
            if (restore != null) {
                onLog("Reloading model…")
                runCatching { inference.load(restore.modelDir!!, restore.backend!!) }
            }
        }
    }
}
