package com.legion.orion.claudecode

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the real `git` CLI baked into the bundled Ubuntu rootfs (see [ClaudeCodeToolchain]) via
 * `proot`, for clone/push/pull/status/branch operations against a project directory. Unlike
 * [ClaudeCodeProcess]'s one persistent session per project, every call here is an independent
 * one-shot subprocess — git operations don't need a long-lived process.
 *
 * Credentials: HTTPS access tokens are embedded directly into the remote URL
 * (`https://<token>@host/owner/repo.git`) at clone/remote-add time — once that's in `.git/config`,
 * push/pull need no further credential handling, and this repo directory already lives in the app's
 * own sandboxed private storage (same trust boundary as [com.legion.orion.core.datastore.SecretStore]).
 * SSH access uses a keypair generated on-device with the rootfs's own `ssh-keygen`; the private key
 * stays inside the rootfs, the public key is surfaced for the user to add to GitHub/GitLab.
 *
 * Known gap: cloned repos have no git identity (`user.name`/`user.email`) configured, so a `git
 * commit` run by the user or by Claude Code's own Bash tool inside one will fail until that's set
 * (either per-repo or as a global default baked into a future rootfs rebuild) — not needed for
 * clone/push/pull/status/branch themselves, only for creating new commits via the git CLI directly.
 */
class GitRemoteManager(private val toolchain: ClaudeCodeToolchain) {

    data class GitResult(val success: Boolean, val output: String)

    data class RepoStatus(
        val branch: String?,
        val ahead: Int,
        val behind: Int,
        val changedFiles: List<String>,
    ) {
        val isDirty: Boolean get() = changedFiles.isNotEmpty()
    }

    private val guestWorkspace = "/root/workspace"
    private val guestProject = "/root/project"

    private fun command(bind: Pair<File, String>?, guestCwd: String, script: String): List<String> = buildList {
        add(toolchain.prootBin.absolutePath)
        // No "-0" (fake root) — real access is governed by the actual Android app UID regardless of
        // what identity proot reports, and faking root tripped up bypassPermissions elsewhere (see
        // ClaudeCodeProcess) — dropped here too for consistency, not because git needs it.
        add("-r"); add(toolchain.rootfsDir.absolutePath)
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        if (bind != null) {
            add("-b"); add("${bind.first.absolutePath}:${bind.second}")
        }
        add("-w"); add(guestCwd)
        add("/bin/bash"); add("-c"); add(script)
    }

    private fun ProcessBuilder.withProotEnv(): ProcessBuilder = apply {
        environment()["PROOT_LOADER"] = toolchain.loaderBin.absolutePath
        environment()["PROOT_LOADER_32"] = toolchain.loader32Bin.absolutePath
        environment()["PROOT_TMP_DIR"] = toolchain.prootTmpDir.absolutePath
        environment()["LD_LIBRARY_PATH"] = toolchain.libDir.absolutePath
    }

    private val envPrefix = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
        "export HOME=/root; export GIT_TERMINAL_PROMPT=0; "

    /** POSIX single-quote-escapes [value] for safe interpolation into the `bash -c` script below.
     * Every value that can come from outside this class (URLs, tokens, branch/remote names — the
     * latter two are attacker-controlled once a repo is cloned, since `git branch -a` surfaces
     * whatever a remote/fork named its branches) MUST go through this before being concatenated into
     * a shell command string; git ref names permit backticks, `$`, `;`, `&`, `|`, quotes, etc. */
    private fun shq(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private suspend fun run(
        bind: Pair<File, String>?,
        guestCwd: String,
        gitCommand: String,
        timeoutSec: Long = 180,
    ): GitResult = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(command(bind, guestCwd, "$envPrefix $gitCommand"))
            .redirectErrorStream(true)
            .withProotEnv()
            .start()
        // Reading on a separate async task (not the awaiting coroutine itself) so a genuinely stuck
        // process (stalled network, blocked on a prompt) can actually be timed out below — plain
        // `readText()` blocks until the process closes its stdout, so awaiting it directly would
        // never reach the `waitFor(timeoutSec, ...)` this timeout used to (uselessly) guard.
        val outputDeferred = async(Dispatchers.IO) {
            runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("")
        }
        val exitedInTime = withTimeoutOrNull(timeoutSec * 1000) {
            while (proc.isAlive) delay(200)
            true
        }
        if (exitedInTime == null) {
            proc.destroyForcibly() // unblocks outputDeferred's readText() by closing its stream
            val output = outputDeferred.await()
            return@withContext GitResult(false, "$output\n(timed out after ${timeoutSec}s)")
        }
        GitResult(proc.exitValue() == 0, outputDeferred.await().trim())
    }

    /** Embeds an HTTPS access token into a github.com/gitlab.com/bitbucket.org-style URL. No-op for
     * SSH URLs (`git@host:...`) or URLs that already carry credentials. */
    private fun withToken(url: String, token: String?): String {
        if (token.isNullOrBlank()) return url
        if (!url.startsWith("https://")) return url
        if (url.substringAfter("https://").contains("@")) return url // already has credentials
        return "https://$token@${url.removePrefix("https://")}"
    }

    /** Derives a reasonable project display name from a repo URL (e.g. "octocat/Hello-World" -> "Hello-World"). */
    fun repoNameFromUrl(url: String): String =
        url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }

    /**
     * Clones [url] directly into [targetDir], which must not already exist (the caller picks the
     * exact final directory — matches `ProjectManager.dirFor(id)`'s convention of the project id
     * determining the directory up front, rather than git's own "name a new subdir" behavior). Pass
     * [token] for a private HTTPS repo — it's embedded in the URL so subsequent push/pull need no
     * further credential handling.
     */
    suspend fun clone(url: String, targetDir: File, token: String? = null): Result<Unit> {
        if (targetDir.exists()) return Result.failure(IllegalStateException("${targetDir.name} already exists"))
        val parent = targetDir.parentFile ?: return Result.failure(IllegalStateException("no parent dir for ${targetDir.path}"))
        parent.mkdirs()
        val effectiveUrl = withToken(url, token)
        val result = run(parent to guestWorkspace, guestWorkspace, "git clone --progress ${shq(effectiveUrl)} ${shq(targetDir.name)} 2>&1")
        return if (result.success && targetDir.isDirectory) {
            Result.success(Unit)
        } else {
            targetDir.deleteRecursively() // clean up a partial clone so a retry doesn't hit "already exists"
            Result.failure(IllegalStateException(result.output.redactTokens()))
        }
    }

    suspend fun status(projectDir: File): RepoStatus? {
        val result = run(projectDir to guestProject, guestProject, "git status --porcelain=v2 --branch 2>&1")
        if (!result.success) return null
        var branch: String? = null
        var ahead = 0
        var behind = 0
        val changed = mutableListOf<String>()
        result.output.lineSequence().forEach { line ->
            when {
                line.startsWith("# branch.head ") -> branch = line.removePrefix("# branch.head ").trim().takeIf { it != "(detached)" }
                line.startsWith("# branch.ab ") -> {
                    val parts = line.removePrefix("# branch.ab ").trim().split(" ")
                    ahead = parts.getOrNull(0)?.removePrefix("+")?.toIntOrNull() ?: 0
                    behind = parts.getOrNull(1)?.removePrefix("-")?.toIntOrNull() ?: 0
                }
                line.startsWith("1 ") || line.startsWith("2 ") || line.startsWith("u ") || line.startsWith("? ") ->
                    changed.add(line.substringAfterLast(' '))
            }
        }
        return RepoStatus(branch, ahead, behind, changed)
    }

    suspend fun push(projectDir: File, remote: String? = null, branch: String? = null): GitResult {
        val args = buildString {
            append("git push")
            if (remote != null) append(" ").append(shq(remote))
            if (branch != null) append(" ").append(shq(branch))
            append(" 2>&1")
        }
        return run(projectDir to guestProject, guestProject, args)
    }

    suspend fun pull(projectDir: File, remote: String? = null, branch: String? = null): GitResult {
        val args = buildString {
            append("git pull")
            if (remote != null) append(" ").append(shq(remote))
            if (branch != null) append(" ").append(shq(branch))
            append(" 2>&1")
        }
        return run(projectDir to guestProject, guestProject, args)
    }

    suspend fun listBranches(projectDir: File): List<String> {
        val result = run(projectDir to guestProject, guestProject, "git branch -a --format='%(refname:short)' 2>&1")
        if (!result.success) return emptyList()
        return result.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    suspend fun checkout(projectDir: File, branch: String, create: Boolean = false): GitResult {
        val flag = if (create) "-b " else ""
        return run(projectDir to guestProject, guestProject, "git checkout $flag${shq(branch)} 2>&1")
    }

    suspend fun addRemote(projectDir: File, name: String, url: String, token: String? = null): GitResult {
        val effectiveUrl = withToken(url, token)
        val addResult = run(projectDir to guestProject, guestProject, "git remote add ${shq(name)} ${shq(effectiveUrl)} 2>&1")
        val result = if (addResult.success) addResult
        else run(projectDir to guestProject, guestProject, "git remote set-url ${shq(name)} ${shq(effectiveUrl)} 2>&1")
        return result.copy(output = result.output.redactTokens())
    }

    suspend fun remotes(projectDir: File): List<String> {
        val result = run(projectDir to guestProject, guestProject, "git remote -v 2>&1")
        if (!result.success) return emptyList()
        return result.output.lineSequence().map { it.substringBefore('\t').trim() }.filter { it.isNotEmpty() }.distinct().toList()
    }

    /** Generates an ed25519 SSH keypair inside the rootfs on first call; returns the public key text
     * to show the user (to add to GitHub/GitLab). Safe to call repeatedly — no-ops if already present. */
    suspend fun ensureSshKey(): Result<String> {
        val pubFile = File(toolchain.rootfsDir, "root/.ssh/id_ed25519.pub")
        if (existsAsEntry(pubFile)) return runCatching { pubFile.readText().trim() }
        val result = run(
            null,
            "/root",
            "mkdir -p /root/.ssh && chmod 700 /root/.ssh && " +
                "ssh-keygen -t ed25519 -f /root/.ssh/id_ed25519 -N '' -C 'orion-mobile' 2>&1",
        )
        if (!result.success) return Result.failure(IllegalStateException(result.output))
        return runCatching { pubFile.readText().trim() }
    }

    private fun String.redactTokens(): String = replace(Regex("https://[^@/\\s]+@"), "https://***@")
}
