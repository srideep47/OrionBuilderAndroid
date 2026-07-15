package com.legion.orion.claudecode

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Locates the bundled on-device Claude Code runtime: a Termux-patched `proot` binary (+ its loader
 * companions + two dependency shared libs it needs — libtalloc/libandroid-shmem — that aren't part
 * of proot's own package), and a trimmed Ubuntu 24.04 arm64 rootfs with the official `claude` CLI
 * pre-installed at `~/.local/bin/claude` inside it.
 *
 * This is a deliberately separate, standalone counterpart to
 * [com.legion.orion.runtime.buildtools.BuildToolchain] (same shape: path accessors + [status]) rather
 * than a shared abstraction, so the proven JDK/Gradle build toolchain is never touched by this feature.
 */
class ClaudeCodeToolchain(context: Context) {

    val root: File = File(context.filesDir, "claudecode")

    val prootBin: File get() = File(root, "proot")
    val loaderBin: File get() = File(root, "loader")
    val loader32Bin: File get() = File(root, "loader32")
    val libDir: File get() = File(root, "lib")

    val rootfsDir: File get() = File(root, "rootfs")

    /** proot needs a writable scratch dir for its own temp files (its default location doesn't exist
     * on Android); without this, tool calls that write files fail with a permission-flavored error
     * that is actually proot's own plumbing, not a Claude Code problem — discovered the hard way. */
    val prootTmpDir: File get() = File(root, "proot-tmp")

    /** The `claude` binary as seen from the HOST filesystem (i.e. inside the rootfs), not the guest
     * path (`/root/.local/bin/claude`) it will be invoked at once chrooted. */
    val claudeBinOnHost: File get() = File(rootfsDir, "root/.local/bin/claude")

    /** Real `git` baked into the rootfs (see [com.legion.orion.claudecode.ClaudeCodeInstaller.ROOTFS_ASSET_VERSION]
     * — `apt-get install` cannot run again on-device, hard links are unsupported under proot on this
     * device so dpkg gets permanently stuck after its first atomic status-file swap; anything needed
     * must be baked into the shipped rootfs asset at packaging time). */
    val gitBinOnHost: File get() = File(rootfsDir, "usr/bin/git")

    /** Records which [com.legion.orion.claudecode.ClaudeCodeInstaller.ROOTFS_ASSET_VERSION] is
     * currently extracted, so a bundled-asset update forces a re-extraction instead of silently
     * keeping stale content (a prior rootfs existing is not by itself a reliable "up to date" signal). */
    val rootfsVersionFile: File get() = File(root, "rootfs.version")

    fun status(): Status {
        val missing = buildList {
            if (!existsAsEntry(prootBin)) add("proot")
            if (!existsAsEntry(loaderBin)) add("proot loader")
            if (!existsAsEntry(File(libDir, "libtalloc.so.2"))) add("libtalloc")
            if (!existsAsEntry(File(libDir, "libandroid-shmem.so"))) add("libandroid-shmem")
            if (!existsAsEntry(File(rootfsDir, "etc/os-release"))) add("Ubuntu rootfs")
            if (!existsAsEntry(claudeBinOnHost)) add("claude CLI") // symlink; see existsAsEntry doc
            if (!existsAsEntry(gitBinOnHost)) add("git")
        }
        return Status(provisioned = missing.isEmpty(), missing = missing)
    }

    data class Status(val provisioned: Boolean, val missing: List<String>) {
        fun describe(): String =
            if (provisioned) "Claude Code runtime ready"
            else "Claude Code runtime not provisioned (missing: ${missing.joinToString(", ")}). " +
                "Install it from the Claude Code sign-in screen first."
    }
}

/** Whether [file] exists as an entry (regular file, dir, OR symlink) without following symlinks.
 * `claude` (and several rootfs files) are symlinks whose targets are absolute paths meant to be
 * resolved *inside* the proot chroot (e.g. `/root/.local/share/claude/...`) — from the real Android
 * filesystem root those targets don't exist, so plain `File.exists()` (which follows symlinks)
 * reports a freshly-installed `claude` as missing. Presence of the entry itself is what matters here,
 * not whether its target resolves on the host. */
internal fun existsAsEntry(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
