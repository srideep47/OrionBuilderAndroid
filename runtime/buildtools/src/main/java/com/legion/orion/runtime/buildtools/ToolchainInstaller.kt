package com.legion.orion.runtime.buildtools

import android.content.res.AssetManager
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * Acquires and extracts the on-device Android build toolchain. Components are `.tar.xz` archives
 * sourced from one of three places (see [Component.source]): a bundled APK asset (`asset:` — the
 * default self-contained path), a local file (an `adb push`ed archive), or an http(s) URL.
 *
 * We stream source → XZ → tar entirely in pure JVM (commons-compress + tukaani), preserving exec bits
 * and symlinks, so no shell/`tar`/`xz` binary is needed before the toolchain exists. The bundled JDK
 * is a standalone bionic arm64 OpenJDK 17 (Termux's openjdk-17 with its dependency .so injected).
 */
class ToolchainInstaller(
    private val toolchain: BuildToolchain,
    private val assets: AssetManager? = null,
) {

    private val http = OkHttpClient()

    data class Component(
        val name: String,
        /** An http(s) URL or an absolute local file path (for archives pushed to the device). */
        val source: String,
        /** Extraction root relative to the toolchain dir (matches the archive's top-level layout). */
        val extractInto: String,
        /** A file (relative to the toolchain dir) that proves this component is installed. */
        val sentinel: String,
    )

    /**
     * Installs any [components] whose sentinel is missing, streaming progress to [onProgress].
     * Returns the resulting toolchain status. Skips already-installed components (idempotent).
     */
    suspend fun install(
        components: List<Component> = defaultComponents(),
        onProgress: (String) -> Unit = {},
    ): BuildToolchain.Status = withContext(Dispatchers.IO) {
        toolchain.root.mkdirs()
        for (c in components) {
            val sentinel = File(toolchain.root, c.sentinel)
            if (sentinel.exists()) { onProgress("${c.name}: already installed"); continue }
            onProgress("${c.name}: installing…")
            val dest = File(toolchain.root, c.extractInto).apply { mkdirs() }
            runCatching { extractFrom(c.source, dest, onProgress) }
                .onFailure { onProgress("${c.name}: FAILED — ${it.message}") }
                .onSuccess { onProgress("${c.name}: installed") }
        }
        toolchain.status()
    }

    /** Opens a bundled asset (`asset:path`), an http(s) URL, or a local file path as a stream. */
    private inline fun <R> openSource(source: String, block: (InputStream) -> R): R = when {
        source.startsWith("asset:") -> {
            val am = assets ?: error("no AssetManager for $source")
            am.open(source.removePrefix("asset:")).use(block)
        }
        source.startsWith("http") ->
            http.newCall(Request.Builder().url(source).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} for $source")
                block((resp.body ?: error("empty body for $source")).byteStream())
            }
        else -> File(source).inputStream().use(block)
    }

    private fun extractFrom(source: String, into: File, onProgress: (String) -> Unit) {
        openSource(source) { stream ->
            TarArchiveInputStream(XZCompressorInputStream(stream.buffered())).use { tar ->
                var entry = tar.nextTarEntry
                var count = 0
                while (entry != null) {
                    val outFile = File(into, entry.name)
                    if (!outFile.canonicalPath.startsWith(into.canonicalPath)) {
                        error("tar entry escapes target dir: ${entry.name}")  // zip-slip guard
                    }
                    when {
                        entry.isDirectory -> outFile.mkdirs()
                        entry.isSymbolicLink -> {
                            outFile.parentFile?.mkdirs()
                            outFile.delete()
                            runCatching { Files.createSymbolicLink(Paths.get(outFile.path), Paths.get(entry.linkName)) }
                        }
                        else -> {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { tar.copyTo(it) }
                            // Preserve the exec bit (aapt2/d8/zipalign/java are executables).
                            if (entry.mode and 0b001_001_001 != 0) outFile.setExecutable(true, false)
                        }
                    }
                    if (++count % 200 == 0) onProgress("  …extracted $count files")
                    entry = tar.nextTarEntry
                }
                onProgress("  extracted $count files")
            }
        }
    }

    companion object {
        private const val BASE = "https://github.com/AndroidIDEOfficial/androidide-tools/releases/download"

        // Component definitions are layout-stable; only the source (URL vs local file) varies.
        private fun sdk(source: String) = Component("Android SDK (platform-34)", source, ".", "android-sdk/platforms/android-34/android.jar")
        private fun buildTools(source: String) = Component("Build-tools 34.0.4", source, "android-sdk", "android-sdk/build-tools/34.0.4/aapt2")
        private fun platformTools(source: String) = Component("Platform-tools", source, "android-sdk", "android-sdk/platform-tools/adb")
        // The standalone arm64 JDK 17 (Termux JDK + injected dep libs) — archive top-level is jdk/.
        private fun jdk(source: String) = Component("OpenJDK 17 (arm64)", source, ".", "jdk/bin/java")

        /** HTTP components from the AndroidIDE arm64 release artifacts. JDK is added via [withJdk]. */
        fun defaultComponents(): List<Component> = listOf(
            sdk("$BASE/sdk/android-sdk.tar.xz"),
            buildTools("$BASE/v34.0.4/build-tools-34.0.4-aarch64.tar.xz"),
            platformTools("$BASE/v34.0.4/platform-tools-34.0.4-aarch64.tar.xz"),
        )

        /** The self-contained path: SDK + build-tools + JDK from archives bundled in assets/toolchain/. */
        fun assetComponents(): List<Component> = listOf(
            sdk("asset:toolchain/android-sdk.tar.xz"),
            buildTools("asset:toolchain/build-tools-34.0.4-aarch64.tar.xz"),
            jdk("asset:toolchain/jdk17-android-arm64.tar.xz"),
        )

        /** Default HTTP components plus a standalone arm64 OpenJDK 17 from [jdkSource] (URL or path). */
        fun withJdk(jdkSource: String): List<Component> = defaultComponents() + jdk(jdkSource)

        /**
         * Components sourced from `.tar.xz` archives pushed into [dir] (the device-side flow:
         * `adb push` the staged toolchain, then install locally). Only archives present are returned.
         */
        fun componentsFromDir(dir: File): List<Component> {
            val map = listOf(
                "android-sdk.tar.xz" to ::sdk,
                "build-tools-34.0.4-aarch64.tar.xz" to ::buildTools,
                "platform-tools-34.0.4-aarch64.tar.xz" to ::platformTools,
                "jdk17-android-arm64.tar.xz" to ::jdk,
            )
            return map.mapNotNull { (file, make) ->
                File(dir, file).takeIf { it.isFile }?.let { make(it.absolutePath) }
            }
        }
    }
}
