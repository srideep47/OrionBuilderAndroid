package com.legion.orion.grok

import com.legion.orion.claudecode.ClaudeCodeToolchain
import com.legion.orion.claudecode.existsAsEntry
import java.io.File

/**
 * Locates the on-device Grok Build CLI. Thin adapter over [ClaudeCodeToolchain] — same shared proot +
 * Ubuntu rootfs as Claude Code and Codex, so all three engines see the same projects, git, terminal,
 * and `orion-tool` bridge.
 *
 * The `grok` binary is **not** baked into the rootfs asset: it is downloaded on first use
 * ([GrokInstaller]) so a Grok install never forces a rootfs re-extract that would wipe Claude/Codex
 * auth and sessions.
 */
class GrokToolchain(val base: ClaudeCodeToolchain) {

    /** Host path for the guest `/root/.local/bin/grok` binary. */
    val grokBinOnHost: File get() = File(base.rootfsDir, "root/.local/bin/grok")

    /** Version sentinel next to the Claude rootfs metadata (not inside the chroot tree). */
    val grokVersionFile: File get() = File(base.root, "grok.version")

    /** `$GROK_HOME` inside the rootfs: `auth.json`, `config.toml`, `sessions/`. */
    val grokHomeOnHost: File get() = File(base.rootfsDir, "root/.grok")

    val installedVersion: String?
        get() = runCatching { grokVersionFile.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun status(): Status {
        val baseStatus = base.status()
        // Grok does not need the `claude` binary; filter it out the same way Codex does.
        val baseMissing = baseStatus.missing.filterNot { it == "claude CLI" }
        val missing = buildList {
            addAll(baseMissing)
            if (!existsAsEntry(grokBinOnHost)) add("Grok Build CLI")
        }
        return Status(provisioned = missing.isEmpty(), missing = missing, installedVersion = installedVersion)
    }

    data class Status(
        val provisioned: Boolean,
        val missing: List<String>,
        val installedVersion: String?,
    ) {
        fun describe(): String =
            if (provisioned) "Grok Build runtime ready${installedVersion?.let { " ($it)" }.orEmpty()}"
            else "Grok Build runtime not provisioned (missing: ${missing.joinToString(", ")})."

        /** Shared rootfs is ready; only the Grok binary needs downloading. */
        val needsOnlyGrokBinary: Boolean get() = missing == listOf("Grok Build CLI")
    }
}
