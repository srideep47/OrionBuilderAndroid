package com.legion.orion.grok

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.brotli.dec.BrotliInputStream

/**
 * Downloads the official Grok Build CLI (`@xai-official/grok-linux-arm64`) into the shared rootfs at
 * `/root/.local/bin/grok`.
 *
 * The npm platform package ships a **Brotli-compressed** binary (`bin/grok.br`, ~40MB) to stay under
 * npm's tarball size ceiling — the postinstall script on desktop decompresses it with Node's
 * `zlib.brotliDecompressSync`. Here we do the same with pure JVM (`org.brotli:dec`) so the phone
 * never needs Node for provisioning.
 *
 * Same install discipline as [com.legion.orion.codex.CodexInstaller]: clear the version sentinel first,
 * stage beside the real path, atomic rename only on success, never leave a half-written binary that
 * a version sentinel would vouch for.
 */
class GrokInstaller(private val toolchain: GrokToolchain) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    data class Release(val version: String, val downloadUrl: String)

    suspend fun install(
        force: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): GrokToolchain.Status = withContext(Dispatchers.IO) {
        check(
            toolchain.base.status().let { status ->
                status.provisioned ||
                    status.missing.none {
                        it in listOf("proot", "proot loader", "libtalloc", "libandroid-shmem", "Ubuntu rootfs")
                    }
            },
        ) {
            "The Linux runtime must be installed before Grok Build — Orion auto-installs it on first launch."
        }

        onProgress("Looking up the latest Grok Build release…")
        val release = resolveRelease()

        if (!force && toolchain.installedVersion == release.version && toolchain.grokBinOnHost.exists()) {
            onProgress("Grok Build ${release.version}: already installed")
            return@withContext toolchain.status()
        }

        onProgress("Downloading Grok Build ${release.version} (~40 MB compressed)…")
        val target = toolchain.grokBinOnHost
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, "grok.download")
        staging.delete()

        try {
            toolchain.grokVersionFile.delete()
            downloadDecompressAndInstall(release, staging, onProgress)
            check(staging.length() > 0) { "Downloaded Grok binary is empty" }
            staging.setExecutable(true, false)
            if (!staging.renameTo(target)) {
                target.delete()
                check(staging.renameTo(target)) { "Could not move the Grok binary into the rootfs" }
            }
            toolchain.grokVersionFile.writeText(release.version)
            onProgress("Grok Build ${release.version}: installed")
        } catch (t: Throwable) {
            staging.delete()
            throw t
        }

        toolchain.status()
    }

    private fun downloadDecompressAndInstall(release: Release, staging: File, onProgress: (String) -> Unit) {
        val request = Request.Builder().url(release.downloadUrl).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Grok download failed: HTTP ${response.code}" }
            val body = response.body ?: error("Grok download returned no body")
            val totalBytes = body.contentLength()

            val counting = CountingInputStream(body.byteStream()) { read ->
                val label = if (totalBytes > 0) {
                    val pct = (read * 100 / totalBytes).coerceIn(0, 100)
                    "Downloading Grok Build ${release.version}: $pct% (${read / 1_048_576} MB)"
                } else {
                    "Downloading Grok Build ${release.version}: ${read / 1_048_576} MB"
                }
                onProgress(label)
            }

            var foundBr = false
            TarArchiveInputStream(GzipCompressorInputStream(counting.buffered())).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    if (!entry.isFile) continue
                    val name = entry.name.replace('\\', '/')
                    // npm pack layout: package/bin/grok.br
                    if (!name.endsWith("bin/grok.br") && !name.endsWith("/grok.br")) continue
                    onProgress("Unpacking Grok Build ${release.version}…")
                    BrotliInputStream(tar).use { brotli ->
                        staging.outputStream().buffered().use { out -> brotli.copyTo(out) }
                    }
                    foundBr = true
                    break
                }
            }
            check(foundBr) {
                "No grok.br binary found inside ${release.downloadUrl.substringAfterLast('/')}"
            }
            onProgress("Grok Build ${release.version}: decompressed, finishing up…")
        }
    }

    /**
     * Resolves the latest stable version from the npm registry, falling back to a pinned version when
     * the registry is unreachable or rate-limited.
     */
    private fun resolveRelease(): Release = runCatching {
        val request = Request.Builder()
            .url("https://registry.npmjs.org/@xai-official/grok/latest")
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val root = json.parseToJsonElement(response.body!!.string()) as JsonObject
            val version = (root["version"] as? JsonPrimitive)?.contentOrNull
                ?: error("no version in npm latest")
            Release(version, tarballUrl(version))
        }
    }.getOrElse {
        Release(PINNED_VERSION, tarballUrl(PINNED_VERSION))
    }

    private fun tarballUrl(version: String): String =
        "https://registry.npmjs.org/@xai-official/grok-linux-arm64/-/grok-linux-arm64-$version.tgz"

    companion object {
        /** Verified present with `package/bin/grok.br` layout (npm, 2026-08). */
        private const val PINNED_VERSION = "0.2.118"
    }
}

private class CountingInputStream(
    private val delegate: InputStream,
    private val onProgress: (Long) -> Unit,
) : InputStream() {
    private var total = 0L
    private var lastReported = 0L

    override fun read(): Int = delegate.read().also { if (it >= 0) advance(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        delegate.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

    private fun advance(n: Long) {
        total += n
        if (total - lastReported >= REPORT_EVERY_BYTES) {
            lastReported = total
            onProgress(total)
        }
    }

    override fun close() = delegate.close()

    private companion object {
        const val REPORT_EVERY_BYTES = 2L * 1024 * 1024
    }
}
