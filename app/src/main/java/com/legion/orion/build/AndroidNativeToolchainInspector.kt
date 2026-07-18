package com.legion.orion.build

import java.io.File
import java.io.RandomAccessFile

/**
 * Performs the checks that AGP otherwise delays until an external-native-build task is reached.
 * Android's official SDK packages contain Linux x86-64 host tools; those binaries cannot execute
 * directly on an ARM64 phone. Detecting that before Gradle starts avoids wasting many minutes and
 * several gigabytes of cache before reporting a misleading "No such file or directory" error.
 */
object AndroidNativeToolchainInspector {

    data class Executable(
        val file: File,
        val architecture: String,
        val hostCompatible: Boolean,
    )

    data class Report(
        val nativeBuildLikely: Boolean,
        val requestedNdkVersion: String?,
        val ndkCompiler: Executable?,
        val cmakeExecutables: List<Executable>,
        val issues: List<String>,
    ) {
        val ready: Boolean get() = issues.isEmpty()

        fun describe(): String = buildString {
            append(if (nativeBuildLikely) "Native Android build detected." else "No native Android build detected.")
            requestedNdkVersion?.let { append(" Requested NDK: ").append(it).append('.') }
            ndkCompiler?.let { append(" NDK host: ").append(it.architecture).append('.') }
            if (cmakeExecutables.isNotEmpty()) {
                append(" CMake hosts: ")
                append(cmakeExecutables.joinToString { "${it.file.parentFile?.parentFile?.name}:${it.architecture}" })
                append('.')
            }
            if (issues.isNotEmpty()) append(" ").append(issues.joinToString(" "))
        }
    }

    fun inspect(layout: AndroidProjectLayout, sdk: File): Report {
        val nativeBuildLikely = isNativeBuildLikely(layout.workspaceRoot, layout.gradleRoot)
        val requestedNdk = requestedNdkVersion(layout.workspaceRoot, layout.gradleRoot)
        val ndkDir = requestedNdk?.let { File(sdk, "ndk/$it") }
            ?.takeIf(File::isDirectory)
            ?: File(sdk, "ndk").listFiles(File::isDirectory)?.maxByOrNull { it.name }
        val ndkCompilerFile = ndkDir
            ?.resolve("toolchains/llvm/prebuilt")
            ?.listFiles(File::isDirectory)
            ?.asSequence()
            ?.map { File(it, "bin/clang") }
            ?.firstOrNull(File::isFile)
        val ndkCompiler = ndkCompilerFile?.let(::executable)
        val cmakeExecutables = File(sdk, "cmake")
            .listFiles(File::isDirectory)
            .orEmpty()
            .mapNotNull { File(it, "bin/cmake").takeIf(File::isFile)?.let(::executable) }
            .sortedBy { it.file.path }

        val issues = if (!nativeBuildLikely) emptyList() else buildList {
            if (requestedNdk != null && !File(sdk, "ndk/$requestedNdk").isDirectory) {
                add("NDK $requestedNdk is not installed.")
            }
            when {
                ndkCompiler == null -> add("No NDK compiler is installed.")
                !ndkCompiler.hostCompatible -> add(
                    "The installed NDK compiler is ${ndkCompiler.architecture}; Orion needs an ARM64-host NDK on this phone.",
                )
            }
            when {
                cmakeExecutables.isEmpty() -> add("CMake is not installed in the Android SDK.")
                cmakeExecutables.none { it.hostCompatible } -> add(
                    "All installed SDK CMake binaries are incompatible with the ARM64 phone " +
                        "(${cmakeExecutables.joinToString { it.architecture }}).",
                )
            }
        }
        return Report(nativeBuildLikely, requestedNdk, ndkCompiler, cmakeExecutables, issues)
    }

    private fun isNativeBuildLikely(workspace: File, gradleRoot: File): Boolean {
        // React Native dependencies frequently contain thousands of nested files. The manifest is
        // both authoritative enough for this preflight and dramatically cheaper than traversing
        // node_modules before each build.
        if (File(workspace, "package.json").takeIf(File::isFile)?.let { file ->
                runCatching {
                    val text = file.readText()
                    text.contains("\"react-native\"") || text.contains("react-native-reanimated")
                }.getOrDefault(false)
            } == true
        ) return true
        val buildFiles = sequenceOf(
            File(gradleRoot, "build.gradle"),
            File(gradleRoot, "build.gradle.kts"),
        ) + gradleRoot.walkTopDown()
            .onEnter { it == gradleRoot || it.name !in ignoredDirectories }
            .maxDepth(4)
            .filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }
        if (buildFiles.distinct().any { file ->
                runCatching { file.readText().contains("externalNativeBuild") }.getOrDefault(false)
            }
        ) return true
        if (workspace.walkTopDown()
                .onEnter { it == workspace || it.name !in ignoredDirectories }
                .maxDepth(7)
                .any { it.isFile && it.name == "CMakeLists.txt" }
        ) return true
        return false
    }

    private fun requestedNdkVersion(workspace: File, gradleRoot: File): String? {
        val candidates = listOf(
            File(gradleRoot, "build.gradle"),
            File(gradleRoot, "build.gradle.kts"),
            File(gradleRoot, "gradle.properties"),
            File(workspace, "build.gradle"),
            File(workspace, "build.gradle.kts"),
        ).distinct().filter(File::isFile)
        val patterns = listOf(
            Regex("""ndkVersion\s*(?:=)?\s*[\"']([0-9][^\"']*)[\"']"""),
            Regex("""ndk\.version\s*=\s*([^\s#]+)"""),
        )
        return candidates.firstNotNullOfOrNull { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@firstNotNullOfOrNull null
            patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
        }
    }

    private fun executable(file: File): Executable {
        val architecture = elfArchitecture(file)
        return Executable(file, architecture, architecture == "arm64" || architecture == "script")
    }

    /** Reads ELF e_machine directly; invoking an incompatible binary would only return ENOENT. */
    private fun elfArchitecture(file: File): String = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(20)
            if (input.read(header) != header.size) return@use "unknown"
            if (header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()) return@use "script"
            if (!(header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte())) return@use "unknown"
            val littleEndian = header[5].toInt() == 1
            val lo = header[18].toInt() and 0xff
            val hi = header[19].toInt() and 0xff
            val machine = if (littleEndian) lo or (hi shl 8) else hi or (lo shl 8)
            when (machine) {
                183 -> "arm64"
                62 -> "x86_64"
                40 -> "arm32"
                3 -> "x86"
                else -> "elf-$machine"
            }
        }
    }.getOrDefault("unknown")

    private val ignoredDirectories = setOf(".git", ".gradle", "build", "dist", "out", ".idea", "node_modules")
}
