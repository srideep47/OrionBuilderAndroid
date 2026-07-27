package com.legion.orion.codex

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * Downloads the Codex CLI into the shared rootfs at `/root/.local/bin/codex`.
 *
 * Unlike [com.legion.orion.claudecode.ClaudeCodeInstaller], which extracts a bundled APK asset, this
 * fetches from GitHub at runtime — see [CodexToolchain] for why bundling it was rejected. That makes
 * this the app's only network-dependent provisioning step, so failures here are ordinary and expected
 * (offline, captive portal, rate limit) and must leave nothing half-installed behind.
 *
 * The release artifact is a **static musl** binary, which matters more than it sounds: it has no libc
 * or shared-library dependency on the Ubuntu rootfs at all, so unlike `claude` it cannot break from a
 * rootfs rebuild, and it sidesteps the whole class of loader/`LD_LIBRARY_PATH` problems that made the
 * proot bring-up painful. The tarball holds exactly one entry — a ~257MB executable — which is why it
 * is streamed straight from the socket through gunzip+untar into its final path: buffering the 105MB
 * download and then expanding it would need ~360MB of free space instead of ~257MB.
 */
class CodexInstaller(private val toolchain: CodexToolchain) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        // A 105MB download over a phone connection is routinely slower than any default timeout, and
        // the read timeout applies per-chunk, not to the whole body.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // no ceiling on the whole transfer
        .build()

    data class Release(val tag: String, val downloadUrl: String)

    /**
     * Installs Codex unless [Release.tag] is already the installed version.
     *
     * @param force re-download even when the version sentinel already matches, for recovering from a
     *   truncated or corrupted binary that still left a sentinel behind.
     */
    suspend fun install(
        force: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): CodexToolchain.Status = withContext(Dispatchers.IO) {
        check(toolchain.base.status().let { it.provisioned || it.missing == listOf("claude CLI") }) {
            "The Linux runtime must be installed before Codex — install it from the agent sign-in screen first."
        }

        onProgress("Looking up the latest Codex release…")
        val release = resolveRelease()

        if (!force && toolchain.installedVersion == release.tag && toolchain.codexBinOnHost.exists()) {
            onProgress("Codex CLI ${release.tag}: already installed")
            return@withContext toolchain.status()
        }

        onProgress("Downloading Codex ${release.tag} (about 105 MB, expands to about 257 MB)…")
        val target = toolchain.codexBinOnHost
        target.parentFile?.mkdirs()
        // Stage beside the real path so an interrupted download can never be mistaken for a working
        // binary, then swap into place only once the whole stream has been consumed successfully.
        val staging = File(target.parentFile, "codex.download")
        staging.delete()

        try {
            // The version sentinel is cleared FIRST. If this process is killed mid-write (very possible
            // — Android is aggressive about backgrounded apps and this runs for minutes), the next
            // launch must see "not installed" rather than a sentinel vouching for a truncated binary.
            toolchain.codexVersionFile.delete()

            downloadAndExtract(release, staging, onProgress)

            check(staging.length() > 0) { "Downloaded Codex binary is empty" }
            staging.setExecutable(true, false)
            if (!staging.renameTo(target)) {
                // Same filesystem, so a rename failure means the destination is in the way.
                target.delete()
                check(staging.renameTo(target)) { "Could not move the Codex binary into the rootfs" }
            }
            toolchain.codexVersionFile.writeText(release.tag)
            onProgress("Codex CLI ${release.tag}: installed")
        } catch (t: Throwable) {
            staging.delete()
            throw t
        }

        toolchain.status()
    }

    private fun downloadAndExtract(release: Release, staging: File, onProgress: (String) -> Unit) {
        val request = Request.Builder().url(release.downloadUrl).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Codex download failed: HTTP ${response.code}" }
            val body = response.body ?: error("Codex download returned no body")
            val totalBytes = body.contentLength()

            // Report progress against COMPRESSED bytes off the wire rather than bytes written: the tar
            // entry's declared size is the uncompressed 257MB, so mixing the two would show a
            // percentage that runs past 100 or stalls confusingly near the end.
            val counting = CountingInputStream(body.byteStream()) { read ->
                val label = if (totalBytes > 0) {
                    val pct = (read * 100 / totalBytes).coerceIn(0, 100)
                    "Downloading Codex ${release.tag}: $pct% (${read / 1_048_576} MB)"
                } else {
                    "Downloading Codex ${release.tag}: ${read / 1_048_576} MB"
                }
                onProgress(label)
            }

            TarArchiveInputStream(GzipCompressorInputStream(counting.buffered())).use { tar ->
                var wrote = false
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    if (!entry.isFile) continue
                    // The archive is expected to hold exactly one file (verified against
                    // rust-v0.145.0: a lone `codex-aarch64-unknown-linux-musl`). Matching on the name
                    // rather than assuming "first entry" keeps this working if a future release adds a
                    // README or LICENSE alongside the binary.
                    val name = entry.name.substringAfterLast('/')
                    if (!name.startsWith("codex")) continue
                    staging.outputStream().buffered().use { out -> tar.copyTo(out) }
                    wrote = true
                    break
                }
                check(wrote) { "No codex binary found inside ${release.downloadUrl.substringAfterLast('/')}" }
            }
            onProgress("Codex ${release.tag}: unpacked, finishing up…")
        }
    }

    /**
     * Asks GitHub for the newest release carrying an arm64 Linux Codex asset, falling back to a known-good
     * pinned tag.
     *
     * The fallback is not defensive boilerplate — the unauthenticated GitHub API allows only 60 requests
     * an hour per IP, and this repo publishes releases for several different products, so "latest" is
     * not guaranteed to contain a `codex-*` asset at all. Failing the whole install because a version
     * lookup was rate-limited would be a poor trade when a working pinned version is right here.
     */
    private fun resolveRelease(): Release = runCatching {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val root = json.parseToJsonElement(response.body!!.string()) as JsonObject
            val tag = (root["tag_name"] as? JsonPrimitive)?.contentOrNull ?: error("no tag_name")
            val asset = (root["assets"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == ASSET_NAME }
                ?: error("release $tag has no $ASSET_NAME")
            val url = (asset["browser_download_url"] as? JsonPrimitive)?.contentOrNull
                ?: error("asset has no download url")
            Release(tag, url)
        }
    }.getOrElse {
        Release(PINNED_TAG, "https://github.com/$REPO/releases/download/$PINNED_TAG/$ASSET_NAME")
    }

    companion object {
        private const val REPO = "openai/codex"

        /** arm64 Linux, statically linked against musl — see the class doc for why that matters. */
        private const val ASSET_NAME = "codex-aarch64-unknown-linux-musl.tar.gz"

        /** Verified present and correctly shaped (one 0755 entry, 269,360,944 bytes) on 2026-07-27. */
        private const val PINNED_TAG = "rust-v0.145.0"
    }
}

/** Wraps a stream to report cumulative bytes read, throttled so a 105MB transfer doesn't post tens of
 * thousands of UI updates. */
private class CountingInputStream(
    private val delegate: java.io.InputStream,
    private val onProgress: (Long) -> Unit,
) : java.io.InputStream() {
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
