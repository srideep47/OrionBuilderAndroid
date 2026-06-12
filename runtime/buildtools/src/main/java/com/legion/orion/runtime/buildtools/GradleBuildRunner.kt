package com.legion.orion.runtime.buildtools

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of an on-device Gradle build. */
data class BuildResult(
    val success: Boolean,
    val apk: File?,
    val exitCode: Int,
    val log: String,
)

/**
 * Runs Gradle on-device by executing the bundled JDK's `java` launcher against the project's Gradle
 * wrapper jar (`org.gradle.wrapper.GradleWrapperMain`), which downloads the Gradle distribution named
 * in gradle-wrapper.properties on first build. We do NOT spawn an external `gradle` binary. The
 * daemon is disabled — a single foreground build avoids a long-lived JVM competing with the LLM.
 */
class GradleBuildRunner(private val toolchain: BuildToolchain) {

    /**
     * Runs `gradle <tasks>` in [projectDir], streaming each merged stdout/stderr line to [onLog].
     * Returns the process exit code (-1 if the toolchain or wrapper could not be launched).
     */
    suspend fun run(
        projectDir: File,
        tasks: List<String>,
        onLog: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val wrapperJar = File(projectDir, "gradle/wrapper/gradle-wrapper.jar")
        if (!wrapperJar.exists()) {
            onLog("ERROR: gradle wrapper not found at $wrapperJar (scaffold android-kt provides it)"); return@withContext -1
        }
        if (!toolchain.javaBin.exists()) {
            onLog("ERROR: java launcher not found at ${toolchain.javaBin}"); return@withContext -1
        }

        // Point Gradle/AGP at the bundled SDK. Written as local.properties (belt-and-suspenders with
        // the ANDROID_HOME env below) so AGP never reports "SDK location not found".
        File(projectDir, "local.properties").writeText("sdk.dir=${toolchain.androidSdk.absolutePath}\n")

        // The bundled JDK is Termux's, which BAKES Termux prefix paths for user.home and
        // java.io.tmpdir (/data/data/com.termux/...). On-device those don't exist, so Gradle fails
        // immediately. Redirect HOME, TMPDIR, user.home and java.io.tmpdir into the toolchain dir.
        // (Verified on-device: without these the build dies before the first task.)
        val homeDir = toolchain.root.apply { mkdirs() }
        val tmpDir = File(toolchain.root, "tmp").apply { mkdirs() }

        val cmd = buildList {
            add(toolchain.javaBin.absolutePath)
            add("-Xmx1536m")
            add("-Dfile.encoding=UTF-8")
            add("-Duser.home=${homeDir.absolutePath}")
            add("-Djava.io.tmpdir=${tmpDir.absolutePath}")
            add("-classpath"); add(wrapperJar.absolutePath)
            add("org.gradle.wrapper.GradleWrapperMain")
            add("--project-dir"); add(projectDir.absolutePath)
            // CRITICAL on-device: AGP otherwise downloads aapt2 from Maven as a HOST-OS (x86) binary
            // that cannot run on arm64 Android. Force it to use the bundled arm64 aapt2 from build-tools.
            add("-Pandroid.aapt2FromMavenOverride=${toolchain.aapt2().absolutePath}")
            add("--no-daemon")
            add("--stacktrace")
            addAll(tasks)
        }

        val proc = ProcessBuilder(cmd)
            .directory(projectDir)
            .redirectErrorStream(true)
            .apply {
                environment()["JAVA_HOME"] = toolchain.javaHome.absolutePath
                environment()["ANDROID_HOME"] = toolchain.androidSdk.absolutePath
                environment()["ANDROID_SDK_ROOT"] = toolchain.androidSdk.absolutePath
                environment()["GRADLE_USER_HOME"] = toolchain.gradleUserHome.absolutePath
                environment()["HOME"] = homeDir.absolutePath
                environment()["TMPDIR"] = tmpDir.absolutePath
                // libjvm.so's RUNPATH points at the dead Termux prefix; its injected dep shims
                // (libandroid-shmem/spawn, libiconv, libz.so.1, …) live in jdk/lib, so make them
                // discoverable explicitly.
                val jdkLib = File(toolchain.javaHome, "lib")
                environment()["LD_LIBRARY_PATH"] =
                    listOf(File(jdkLib, "server"), jdkLib).joinToString(File.pathSeparator) { it.absolutePath }
            }
            .start()

        proc.inputStream.bufferedReader().forEachLine(onLog)
        proc.waitFor()
    }

    /** Builds a debug APK (AGP auto-signs debug with a generated debug keystore) and locates the artifact. */
    suspend fun assembleDebugApk(projectDir: File, onLog: (String) -> Unit): BuildResult {
        val log = StringBuilder()
        val code = run(projectDir, listOf(":app:assembleDebug"), { line -> log.appendLine(line); onLog(line) })
        val apk = if (code == 0) findApk(projectDir) else null
        return BuildResult(success = code == 0 && apk != null, apk = apk, exitCode = code, log = log.toString())
    }

    private fun findApk(projectDir: File): File? =
        File(projectDir, "app/build/outputs/apk/debug")
            .listFiles { f -> f.extension == "apk" }
            ?.firstOrNull()
            ?: projectDir.walkTopDown()
                .firstOrNull { it.path.contains("outputs/apk") && it.extension == "apk" }
}
