package com.legion.orion.runtime.buildtools

import android.content.Context
import java.io.File

/**
 * Locates the bundled on-device Android build toolchain: OpenJDK 17 and the Android SDK (platform-33
 * + build-tools 34.0.4, the arm64 builds AndroidIDE publishes). Gradle itself is NOT bundled — each
 * project carries a Gradle wrapper and the JDK bootstraps the distribution on first build (see
 * [GradleBuildRunner]).
 *
 * The toolchain is provisioned by [ToolchainInstaller] into [root]. [status] reports what is missing
 * so the build tools refuse to run with a clear message until everything is present.
 *
 * NOTE (device-gated): executing these binaries (java, aapt2, d8, …) from app-private storage is
 * blocked by W^X on targetSdk ≥ 29. Whether Orion runs the build under a permitting configuration
 * (targetSdk 28 fallback) or relocates launchers must be proven on-device — that is the open M4 risk.
 */
class BuildToolchain(private val context: Context) {

    val root: File = File(context.filesDir, "toolchain")
    val javaHome: File = File(root, "jdk")
    val androidSdk: File = File(root, "android-sdk")
    val gradleUserHome: File = File(root, "gradle-user-home")

    /** The JDK `java` launcher inside the extracted JDK image. */
    val javaBin: File get() = File(javaHome, "bin/java")

    fun buildToolsDir(version: String = DEFAULT_BUILD_TOOLS): File = File(androidSdk, "build-tools/$version")
    fun aapt2(version: String = DEFAULT_BUILD_TOOLS): File = File(buildToolsDir(version), "aapt2")

    fun status(): Status {
        val missing = buildList {
            if (!javaBin.exists()) add("jdk (jdk/bin/java)")
            if (!File(androidSdk, "platforms").isDirectory) add("android-platform")
            if (!aapt2().exists()) add("build-tools ($DEFAULT_BUILD_TOOLS)")
        }
        return Status(provisioned = missing.isEmpty(), missing = missing)
    }

    data class Status(val provisioned: Boolean, val missing: List<String>) {
        fun describe(): String =
            if (provisioned) "build toolchain ready"
            else "build toolchain not provisioned (missing: ${missing.joinToString(", ")}). " +
                "Install it from the Build screen first."
    }

    companion object {
        const val DEFAULT_BUILD_TOOLS = "34.0.4"
    }
}
