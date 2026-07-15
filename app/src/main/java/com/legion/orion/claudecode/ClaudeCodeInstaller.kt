package com.legion.orion.claudecode

import android.content.res.AssetManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * Provisions the bundled Claude Code runtime from two `.tar.xz` assets:
 * `claudecode/proot-bundle-arm64.tar.xz` (proot + loader + loader32 + libtalloc.so.2 +
 * libandroid-shmem.so) and `claudecode/rootfs-ubuntu-noble-arm64.tar.xz` (a trimmed Ubuntu 24.04
 * arm64 rootfs with the official `claude` CLI pre-installed, auth state stripped).
 *
 * Extraction mirrors [com.legion.orion.runtime.buildtools.ToolchainInstaller]'s pure-JVM tar.xz
 * streaming (commons-compress + tukaani xz — no shell `tar`/`xz` binary needed before the runtime
 * exists), preserving exec bits and symlinks, with the same zip-slip canonical-path guard. Kept as a
 * standalone implementation (not a shared class) so the proven JDK/Gradle build toolchain path is
 * never touched by this feature.
 */
class ClaudeCodeInstaller(
    private val toolchain: ClaudeCodeToolchain,
    private val assets: AssetManager,
) {

    suspend fun install(onProgress: (String) -> Unit = {}): ClaudeCodeToolchain.Status =
        withContext(Dispatchers.IO) {
            toolchain.root.mkdirs()
            toolchain.prootTmpDir.mkdirs()

            if (!existsAsEntry(toolchain.prootBin)) {
                onProgress("proot runtime: installing…")
                extractAsset(PROOT_ASSET, toolchain.root, onProgress)
                onProgress("proot runtime: installed")
            } else {
                onProgress("proot runtime: already installed")
            }

            val installedVersion = runCatching { toolchain.rootfsVersionFile.readText().trim() }.getOrNull()
            if (installedVersion != ROOTFS_ASSET_VERSION || !existsAsEntry(toolchain.claudeBinOnHost)) {
                onProgress("Ubuntu rootfs + Claude Code CLI: installing (large, please wait)…")
                toolchain.rootfsDir.deleteRecursively() // wipe any stale/partial previous version first
                toolchain.rootfsDir.mkdirs()
                extractAsset(ROOTFS_ASSET, toolchain.rootfsDir, onProgress)
                fixResolvConf()
                toolchain.rootfsVersionFile.writeText(ROOTFS_ASSET_VERSION)
                onProgress("Ubuntu rootfs + Claude Code CLI: installed")
            } else {
                onProgress("Ubuntu rootfs + Claude Code CLI: already installed")
            }

            toolchain.status()
        }

    /**
     * The rootfs ships `/etc/resolv.conf` as a dangling symlink to a systemd-resolved path that only
     * exists once the image is actually booted with systemd — which we never do. Android has no
     * `/etc/resolv.conf` of its own to bind-mount over it either, so replace it with a plain file
     * pointing at public resolvers. Without this, DNS resolution inside the chroot fails entirely.
     */
    private fun fixResolvConf() {
        val resolvConf = File(toolchain.rootfsDir, "etc/resolv.conf")
        resolvConf.delete() // remove the dangling symlink, not its (nonexistent) target
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    }

    private fun extractAsset(assetPath: String, into: File, onProgress: (String) -> Unit) {
        assets.open(assetPath).use { stream ->
            TarArchiveInputStream(XZCompressorInputStream(stream.buffered())).use { tar ->
                val intoNormalized = into.toPath().normalize()
                var entry: TarArchiveEntry? = tar.nextTarEntry
                var count = 0
                while (entry != null) {
                    // Zip-slip guard: purely lexical (Path.normalize does not touch the filesystem or
                    // resolve symlinks). A live-filesystem check via File.canonicalPath is the wrong
                    // tool here — this rootfs legitimately ships masked systemd units as symlinks to
                    // /dev/null, and canonicalPath resolving those against disk produced false
                    // positives ("tar entry escapes target dir") on real entries that don't escape
                    // anything; only the entry's own destination path needs checking, never its link
                    // target (that's just where it points once created, not where it's written).
                    val destPath = intoNormalized.resolve(entry.name).normalize()
                    if (!destPath.startsWith(intoNormalized)) {
                        error("tar entry escapes target dir: ${entry.name}")
                    }
                    val outFile = destPath.toFile()
                    when {
                        entry.isDirectory -> outFile.mkdirs()
                        entry.isSymbolicLink -> {
                            outFile.parentFile?.mkdirs()
                            outFile.delete()
                            runCatching { Files.createSymbolicLink(Paths.get(outFile.path), Paths.get(entry.linkName)) }
                        }
                        entry.isLink -> {
                            // Hard links: the rootfs has a couple (e.g. perl, gunzip aliases) that
                            // can't be created on-device without root; copy the content instead.
                            outFile.parentFile?.mkdirs()
                            val linkedEntryContent = runCatching {
                                File(into, entry.linkName).takeIf { it.exists() }?.copyTo(outFile, overwrite = true)
                            }
                            if (linkedEntryContent.getOrNull() == null) {
                                // Target not extracted yet (or missing) — skip; not needed for our use.
                            }
                        }
                        entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> {
                            // Real device nodes need mknod (root-only) and aren't needed: proot
                            // bind-mounts the host's real /dev over the chroot's /dev at runtime.
                        }
                        else -> {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { tar.copyTo(it) }
                            // Preserve the exec bit (proot/loader/claude are executables).
                            if (entry.mode and 0b001_001_001 != 0) outFile.setExecutable(true, false)
                        }
                    }
                    if (++count % 2000 == 0) onProgress("  …extracted $count files")
                    entry = tar.nextTarEntry
                }
                onProgress("  extracted $count files")
            }
        }
    }

    companion object {
        private const val PROOT_ASSET = "claudecode/proot-bundle-arm64.tar.xz"
        private const val ROOTFS_ASSET = "claudecode/rootfs-ubuntu-noble-arm64.tar.xz"

        /** Bump whenever `rootfs-ubuntu-noble-arm64.tar.xz` is regenerated (new packages baked in,
         * etc.) so already-provisioned devices wipe and re-extract instead of silently keeping the
         * stale content — `claudeBinOnHost` existing is not a reliable "up to date" signal by itself. */
        const val ROOTFS_ASSET_VERSION = "v2-git"
    }
}
