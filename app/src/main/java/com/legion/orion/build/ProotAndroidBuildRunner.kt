package com.legion.orion.build

import com.legion.orion.claudecode.ClaudeCodeToolchain
import com.legion.orion.runtime.buildtools.BuildResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds Android projects inside Orion's provisioned Ubuntu/Claude runtime. This is the same
 * JDK/SDK/Gradle environment used by Claude's Bash tool, exposed to Preview so manual, agent and UI
 * builds all use one proven toolchain rather than requiring a second installation.
 */
class ProotAndroidBuildRunner(private val toolchain: ClaudeCodeToolchain) {

    data class Status(
        val provisioned: Boolean,
        val missing: List<String>,
        val javaHome: String?,
        val gradleHome: String?,
    )

    fun status(): Status {
        val javaHome = File(toolchain.rootfsDir, "root/toolchain")
            .listFiles { file -> file.isDirectory && file.name.startsWith("jdk-17") }
            ?.maxByOrNull { it.name }
        val gradleHome = File(toolchain.rootfsDir, "root/toolchain")
            .listFiles { file -> file.isDirectory && file.name.startsWith("gradle-") }
            ?.maxByOrNull { it.name }
        val sdk = File(toolchain.rootfsDir, "root/toolchain/android-sdk")
        val aapt2 = File(toolchain.rootfsDir, "root/toolchain/aapt2-wrapper/aapt2")
        val missing = buildList {
            if (!toolchain.status().provisioned) add("Claude runtime")
            if (javaHome == null || !File(javaHome, "bin/java").exists()) add("JDK 17")
            if (!File(sdk, "platforms").isDirectory) add("Android platform")
            if (!aapt2.exists()) add("ARM64 aapt2")
            if (gradleHome == null || !File(gradleHome, "bin/gradle").exists()) add("Gradle")
        }
        return Status(
            provisioned = missing.isEmpty(),
            missing = missing,
            javaHome = javaHome?.let { "/root/toolchain/${it.name}" },
            gradleHome = gradleHome?.let { "/root/toolchain/${it.name}" },
        )
    }

    suspend fun assembleDebugApk(layout: AndroidProjectLayout, onLog: (String) -> Unit): BuildResult =
        withContext(Dispatchers.IO) {
            val status = status()
            if (!status.provisioned) {
                return@withContext BuildResult(
                    false,
                    null,
                    -1,
                    "Claude Android toolchain missing: ${status.missing.joinToString()}",
                )
            }
            if (!layout.workspaceRoot.isDirectory || !layout.gradleRoot.isDirectory) {
                return@withContext BuildResult(false, null, -1, "Android project directory no longer exists")
            }

            val sdk = File(toolchain.rootfsDir, "root/toolchain/android-sdk")
            val nativeReport = AndroidNativeToolchainInspector.inspect(layout, sdk)
            onLog(nativeReport.describe())
            if (!nativeReport.ready) {
                return@withContext BuildResult(
                    false,
                    null,
                    -1,
                    "Android native toolchain preflight failed. ${nativeReport.describe()} " +
                        "Install a version-matched ARM64-host CMake/NDK toolchain, or explicitly select a compatible local override.",
                )
            }

            val localProperties = File(layout.gradleRoot, "local.properties")
            val originalLocalProperties = localProperties.takeIf(File::isFile)?.readBytes()
            ensureRootfsSdkLocation(layout.gradleRoot)
            val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
            val memoryMb = readTotalMemoryMb()
            val heapMb = ((memoryMb * 35) / 100).coerceIn(1536L, 4096L).toInt()
            val javaHome = status.javaHome!!
            val gradleHome = status.gradleHome!!
            val gradleCommand = if (layout.hasWrapper) {
                "/bin/bash ./gradlew"
            } else {
                "$gradleHome/bin/gradle"
            }
            val script = buildString {
                append("export HOME=/root TMPDIR=/tmp ")
                append("JAVA_HOME=").append(shq(javaHome)).append(' ')
                append("ANDROID_HOME=/root/toolchain/android-sdk ")
                append("ANDROID_SDK_ROOT=/root/toolchain/android-sdk ")
                append("GRADLE_USER_HOME=/root/.gradle; ")
                append("export PATH=").append(shq("$javaHome/bin:$gradleHome/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")).append("; ")
                append("for d in /root/localtools/*/bin; do [ -d \"\$d\" ] && PATH=\"\$d:\$PATH\"; done; export PATH; ")
                append("export MAKEFLAGS=-j$workers CMAKE_BUILD_PARALLEL_LEVEL=$workers CARGO_BUILD_JOBS=$workers; ")
                append("export GOMAXPROCS=$workers RAYON_NUM_THREADS=$workers OMP_NUM_THREADS=$workers OPENBLAS_NUM_THREADS=$workers; ")
                append("export GRADLE_OPTS=").append(shq("-Xms256m -Xmx${heapMb}m -XX:+UseParallelGC -XX:ActiveProcessorCount=$workers -Dfile.encoding=UTF-8")).append("; ")
                append(gradleCommand)
                append(" -Pandroid.aapt2FromMavenOverride=/root/toolchain/aapt2-wrapper/aapt2")
                append(" -Pkotlin.compiler.execution.strategy=in-process")
                // Preview/install builds run on this ARM64 device. React Native understands this
                // property; other Gradle projects safely ignore it. Multi-ABI release packaging is
                // intentionally left to an explicit release task instead of multiplying local work.
                append(" -PreactNativeArchitectures=arm64-v8a")
                append(" --no-daemon --parallel --build-cache --max-workers=$workers --console=plain --stacktrace ")
                append(shq(layout.assembleDebugTask))
            }
            onLog("Using Orion's shared Claude Android toolchain.")
            onLog(
                "Detected Gradle root '${layout.relativeGradleRoot}', task ${layout.assembleDebugTask}, " +
                    if (layout.hasWrapper) "using the project's wrapper." else "using Orion's fallback Gradle.",
            )
            onLog("Orion performance profile: $workers CPU worker(s), $heapMb MiB Gradle heap, $memoryMb MiB physical RAM")

            val guestGradleRoot = if (layout.relativeGradleRoot == ".") {
                "/root/project"
            } else {
                "/root/project/${layout.relativeGradleRoot}"
            }

            val command = buildList {
                add(toolchain.prootBin.absolutePath)
                add("-r"); add(toolchain.rootfsDir.absolutePath)
                add("-b"); add("/dev")
                add("-b"); add("/proc")
                add("-b"); add("/sys")
                add("-b"); add("${layout.workspaceRoot.absolutePath}:/root/project")
                add("-w"); add(guestGradleRoot)
                add("/bin/bash"); add("-lc"); add(script)
            }
            val log = StringBuilder()
            val process = runCatching {
                ProcessBuilder(command)
                    .directory(layout.gradleRoot)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["PROOT_LOADER"] = toolchain.loaderBin.absolutePath
                        environment()["PROOT_LOADER_32"] = toolchain.loader32Bin.absolutePath
                        environment()["PROOT_TMP_DIR"] = toolchain.prootTmpDir.apply { mkdirs() }.absolutePath
                        environment()["LD_LIBRARY_PATH"] = toolchain.libDir.absolutePath
                    }
                    .start()
            }.getOrElse { error ->
                val message = "Could not start Android build: ${error.message}"
                onLog(message)
                restoreLocalProperties(localProperties, originalLocalProperties)
                return@withContext BuildResult(false, null, -1, message)
            }
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    log.appendLine(line)
                    onLog(line)
                }
                val exitCode = process.waitFor()
                val apk = if (exitCode == 0) AndroidProjectLocator.findApk(layout) else null
                if (exitCode == 0 && apk == null) onLog("Gradle completed successfully, but no installable APK was found.")
                BuildResult(exitCode == 0 && apk != null, apk, exitCode, log.toString())
            } finally {
                restoreLocalProperties(localProperties, originalLocalProperties)
            }
        }

    private fun ensureRootfsSdkLocation(projectDir: File) {
        val localProperties = File(projectDir, "local.properties")
        val retained = if (localProperties.exists()) {
            localProperties.readLines().filterNot { it.trimStart().startsWith("sdk.dir=") }
        } else {
            emptyList()
        }
        localProperties.writeText((retained + "sdk.dir=/root/toolchain/android-sdk").joinToString("\n", postfix = "\n"))
    }

    private fun restoreLocalProperties(file: File, original: ByteArray?) {
        if (original == null) file.delete() else file.writeBytes(original)
    }

    private fun readTotalMemoryMb(): Long = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.first { it.startsWith("MemTotal:") }
                .substringAfter(':').trim().substringBefore(' ').toLong() / 1024L
        }
    }.getOrDefault(4096L)

    private fun shq(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
