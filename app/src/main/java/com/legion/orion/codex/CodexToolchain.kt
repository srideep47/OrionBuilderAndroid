package com.legion.orion.codex

import com.legion.orion.claudecode.ClaudeCodeToolchain
import com.legion.orion.claudecode.existsAsEntry
import java.io.File

/**
 * Locates the on-device Codex CLI. Deliberately a thin adapter over [ClaudeCodeToolchain] rather than a
 * parallel runtime: the proot binary, its loaders, the dependency libs, and the Ubuntu rootfs are all
 * engine-agnostic infrastructure, and provisioning a second 630MB rootfs so Codex could have its own
 * would be absurd. Both engines chroot into the same filesystem and therefore see the same projects,
 * the same `git`, the same `python3`, and the same `orion-tool` bridge.
 *
 * The one thing Codex adds is its own binary at `/root/.local/bin/codex`, which — unlike `claude` — is
 * NOT baked into the shipped rootfs asset. It is downloaded on first use ([CodexInstaller]), for two
 * reasons: the release tarball is ~105MB (it would roughly double the APK's LFS payload), and baking it
 * in would force a [com.legion.orion.claudecode.ClaudeCodeInstaller.ROOTFS_ASSET_VERSION] bump, which
 * wipes and re-extracts the rootfs on every already-provisioned device — destroying its Claude Code
 * subscription login and session ids as collateral damage for a feature the user may not even use.
 */
class CodexToolchain(val base: ClaudeCodeToolchain) {

    /** The `codex` binary as seen from the HOST filesystem; its guest path is `/root/.local/bin/codex`. */
    val codexBinOnHost: File get() = File(base.rootfsDir, "root/.local/bin/codex")

    /** Records the installed Codex release tag so [CodexInstaller] can detect an out-of-date binary
     * without re-downloading 105MB to find out. */
    val codexVersionFile: File get() = File(base.root, "codex.version")

    /** Codex's own state dir (`$CODEX_HOME`) inside the rootfs: `auth.json`, `config.toml`, `sessions/`. */
    val codexHomeOnHost: File get() = File(base.rootfsDir, "root/.codex")

    val installedVersion: String?
        get() = runCatching { codexVersionFile.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Codex needs everything the shared runtime needs, PLUS its own binary. The base rootfs check is
     * delegated rather than reimplemented so a half-provisioned device reports the real first cause
     * ("Ubuntu rootfs") instead of a misleading "Codex CLI missing".
     */
    fun status(): Status {
        val baseStatus = base.status()
        // `claude CLI` is the one base requirement Codex genuinely does not need — don't make a user
        // who only wants Codex look un-provisioned because the other engine's binary is absent.
        val baseMissing = baseStatus.missing.filterNot { it == "claude CLI" }
        val missing = buildList {
            addAll(baseMissing)
            if (!existsAsEntry(codexBinOnHost)) add("Codex CLI")
        }
        return Status(provisioned = missing.isEmpty(), missing = missing, installedVersion = installedVersion)
    }

    data class Status(
        val provisioned: Boolean,
        val missing: List<String>,
        val installedVersion: String?,
    ) {
        fun describe(): String =
            if (provisioned) "Codex runtime ready${installedVersion?.let { " ($it)" }.orEmpty()}"
            else "Codex runtime not provisioned (missing: ${missing.joinToString(", ")})."

        /** True when only Codex's own binary is absent — i.e. the shared rootfs is already good and a
         * download is all that's needed, rather than a full runtime install. */
        val needsOnlyCodexBinary: Boolean get() = missing == listOf("Codex CLI")
    }
}
