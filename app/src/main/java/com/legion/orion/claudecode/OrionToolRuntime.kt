package com.legion.orion.claudecode

import java.io.File

/**
 * Pieces of the on-device agent runtime that are NOT specific to Claude Code, factored out so the
 * Codex engine (`com.legion.orion.codex`) reuses them instead of growing a second, drifting copy.
 *
 * These live in this package rather than a new one because that is where the proot/rootfs runtime they
 * operate on already lives ([ClaudeCodeToolchain]) — the rootfs is shared infrastructure that predates
 * either engine being the only tenant, and moving it now would churn a lot of proven code for a rename.
 */

/**
 * Writes the `orion-tool` bridge client into the rootfs at `/root/.local/bin/orion-tool`.
 *
 * Idempotent and content-addressed: rewritten only when the on-disk copy differs from
 * [ClaudeCodeProcess.ORION_TOOL_CLIENT], so bumping the script in source automatically replaces a
 * stale copy on an already-provisioned device without needing a version sentinel.
 *
 * Both engines invoke this identically — the client is a plain Python script driven entirely by the
 * `ORION_TOOL_PORT`/`ORION_TOOL_TOKEN`/`ORION_PROJECT_HOST` environment variables, with nothing
 * Claude-specific in it, so Codex gets preview QA, device automation, and the whole Godot bridge for
 * free just by having these env vars set on its process.
 */
internal fun ensureOrionToolClient(toolchain: ClaudeCodeToolchain) {
    val client = File(toolchain.rootfsDir, "root/.local/bin/orion-tool")
    client.parentFile?.mkdirs()
    if (!client.exists() || client.readText() != ClaudeCodeProcess.ORION_TOOL_CLIENT) {
        client.writeText(ClaudeCodeProcess.ORION_TOOL_CLIENT)
        client.setExecutable(true, true)
    }
}

/**
 * POSIX single-quote escaping for a value spliced into a `bash -c` script.
 *
 * Required for anything derived from untrusted input — project names, branch names from a cloned
 * remote, and (for Codex) `-c key=value` config overrides whose values include a multi-KB instruction
 * string full of quotes and punctuation. See `GitRemoteManager.shq` for the injection this class of
 * bug caused there.
 */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
