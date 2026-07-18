package com.legion.orion.build

import java.io.File

/** A discovered Android/Gradle application inside an Orion workspace. */
data class AndroidProjectLayout(
    val workspaceRoot: File,
    val gradleRoot: File,
    val applicationModule: File,
) {
    val relativeGradleRoot: String = gradleRoot.relativeTo(workspaceRoot).invariantSeparatorsPath
        .ifBlank { "." }
    val modulePath: String = applicationModule.relativeTo(gradleRoot).invariantSeparatorsPath
    val assembleDebugTask: String = if (modulePath.isBlank()) {
        "assembleDebug"
    } else {
        ":${modulePath.replace('/', ':')}:assembleDebug"
    }
    val hasWrapper: Boolean = File(gradleRoot, "gradle/wrapper/gradle-wrapper.jar").isFile
}

/**
 * Finds Android applications without assuming that the registered Orion project is itself the
 * Gradle root. Real repositories commonly keep Android under `android/` (React Native, Flutter,
 * Capacitor and Cordova) or deeper in a monorepo. Wrapper-backed candidates win so Orion does not
 * silently replace a project's requested Gradle version with its shared fallback Gradle.
 */
object AndroidProjectLocator {
    private val ignoredDirectories = setOf(
        ".git", ".gradle", ".idea", ".orion", "build", "node_modules", "vendor", "dist", "out",
    )

    fun locate(workspace: File, maxDepth: Int = 5): AndroidProjectLayout? {
        val root = runCatching { workspace.canonicalFile }.getOrNull()?.takeIf { it.isDirectory } ?: return null
        return candidateDirectories(root, maxDepth)
            .mapNotNull { candidate ->
                val module = findApplicationModule(candidate, maxDepth = 3) ?: return@mapNotNull null
                val wrapper = File(candidate, "gradle/wrapper/gradle-wrapper.jar").isFile
                val conventionalAndroid = candidate.name.equals("android", ignoreCase = true)
                val depth = relativeDepth(root, candidate)
                val score = (if (wrapper) 10_000 else 0) +
                    (if (conventionalAndroid) 500 else 0) +
                    (if (candidate == root) 250 else 0) - depth
                score to AndroidProjectLayout(root, candidate, module)
            }
            .maxByOrNull { it.first }
            ?.second
    }

    fun findApk(layout: AndroidProjectLayout): File? {
        val preferred = File(layout.applicationModule, "build/outputs/apk")
        return preferred.walkFilesSafely(maxDepth = 5)
            .filter(::isInstallableApk)
            .maxByOrNull(File::lastModified)
            ?: layout.gradleRoot.walkFilesSafely(maxDepth = 8)
                .filter(::isInstallableApk)
                .maxByOrNull(File::lastModified)
    }

    fun applicationId(layout: AndroidProjectLayout): String? {
        val gradle = listOf(
            File(layout.applicationModule, "build.gradle.kts"),
            File(layout.applicationModule, "build.gradle"),
        ).firstOrNull(File::isFile)
        val source = gradle?.let { runCatching { it.readText() }.getOrDefault("") }.orEmpty()
        Regex("applicationId\\s*(?:=\\s*)?[\\\"']([^\\\"']+)[\\\"']")
            .find(source)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("namespace\\s*(?:=\\s*)?[\\\"']([^\\\"']+)[\\\"']")
            .find(source)?.groupValues?.getOrNull(1)?.let { return it }
        val manifest = File(layout.applicationModule, "src/main/AndroidManifest.xml")
        return runCatching {
            Regex("package=[\\\"']([^\\\"']+)[\\\"']")
                .find(manifest.readText())?.groupValues?.getOrNull(1)
        }.getOrNull()
    }

    private fun candidateDirectories(root: File, maxDepth: Int): Sequence<File> = sequence {
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            if (isGradleRoot(dir)) yield(dir)
            if (depth >= maxDepth) continue
            dir.listFiles { file -> file.isDirectory && file.name !in ignoredDirectories }
                ?.sortedBy { if (it.name.equals("android", ignoreCase = true)) 0 else 1 }
                ?.forEach { queue.add(it to depth + 1) }
        }
    }

    private fun isGradleRoot(dir: File): Boolean =
        File(dir, "settings.gradle").isFile ||
            File(dir, "settings.gradle.kts").isFile ||
            File(dir, "gradle/wrapper/gradle-wrapper.properties").isFile

    private fun findApplicationModule(root: File, maxDepth: Int): File? {
        val conventional = File(root, "app")
        if (isApplicationModule(conventional)) return conventional
        if (isApplicationModule(root)) return root
        val queue = ArrayDeque<Pair<File, Int>>()
        root.listFiles { file -> file.isDirectory && file.name !in ignoredDirectories }
            ?.forEach { queue.add(it to 1) }
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            if (isApplicationModule(dir)) return dir
            if (depth >= maxDepth) continue
            dir.listFiles { file -> file.isDirectory && file.name !in ignoredDirectories }
                ?.forEach { queue.add(it to depth + 1) }
        }
        return null
    }

    private fun isApplicationModule(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val buildFile = listOf(File(dir, "build.gradle.kts"), File(dir, "build.gradle"))
            .firstOrNull(File::isFile) ?: return false
        val source = runCatching { buildFile.readText() }.getOrDefault("")
        return source.contains("com.android.application") ||
            Regex("apply\\s+plugin:\\s*[\\\"']com\\.android\\.application[\\\"']").containsMatchIn(source)
    }

    private fun relativeDepth(root: File, child: File): Int =
        runCatching { child.relativeTo(root).toPath().nameCount }.getOrDefault(Int.MAX_VALUE / 2)

    private fun File.walkFilesSafely(maxDepth: Int): Sequence<File> = sequence {
        if (!exists()) return@sequence
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(this@walkFilesSafely to 0)
        while (queue.isNotEmpty()) {
            val (entry, depth) = queue.removeFirst()
            if (entry.isFile) {
                yield(entry)
            } else if (entry.isDirectory && depth < maxDepth && entry.name !in ignoredDirectories) {
                entry.listFiles()?.forEach { queue.add(it to depth + 1) }
            }
        }
    }

    private fun isInstallableApk(file: File): Boolean =
        file.isFile && file.extension.equals("apk", ignoreCase = true) &&
            !file.name.contains("unaligned", ignoreCase = true) &&
            !file.name.contains("unsigned", ignoreCase = true) &&
            file.invariantSeparatorsPath.contains("/outputs/apk/")
}
