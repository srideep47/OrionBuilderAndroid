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
 * Credentials: an HTTPS access token is embedded into the remote URL only for the duration of one
 * clone/push/pull/fetch call (see [withTemporaryCredentials]) and stripped back out immediately
 * after — it does not sit permanently in `.git/config` at rest. The token itself comes from a named
 * git account (see [com.legion.orion.core.datastore.GitAccountStore] /
 * [com.legion.orion.core.datastore.SecretStore]), resolved by the caller and passed in per-call.
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
        val files: List<GitFileState>,
    ) {
        val changedFiles: List<String> get() = files.map { it.path }
        val isDirty: Boolean get() = files.isNotEmpty()
        val staged: List<GitFileState> get() = files.filter { it.indexStatus != ' ' && it.indexStatus != '?' }
        val unstaged: List<GitFileState> get() = files.filter { it.workTreeStatus != ' ' || it.indexStatus == '?' }
        val conflicts: List<GitFileState> get() = files.filter { it.conflicted }
    }

    data class GitFileState(
        val path: String,
        val indexStatus: Char,
        val workTreeStatus: Char,
    ) {
        val conflicted: Boolean get() = "$indexStatus$workTreeStatus" in setOf("DD", "AU", "UD", "UA", "DU", "AA", "UU")
        val label: String get() = when {
            conflicted -> "Conflict"
            indexStatus == '?' -> "Untracked"
            indexStatus != ' ' && workTreeStatus != ' ' -> "Staged + modified"
            indexStatus != ' ' -> "Staged"
            workTreeStatus == 'D' -> "Deleted"
            workTreeStatus == 'M' -> "Modified"
            else -> "$indexStatus$workTreeStatus".trim()
        }
    }

    data class GitCommit(val hash: String, val shortHash: String, val author: String, val relativeTime: String, val subject: String)
    data class GitRemote(val name: String, val fetchUrl: String?, val pushUrl: String?)
    data class GitIdentity(val name: String?, val email: String?)

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
        onOutput: ((String) -> Unit)? = null,
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
            runCatching {
                val output = StringBuilder()
                proc.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val chunk = String(buffer, 0, count)
                        output.append(chunk)
                        onOutput?.invoke(chunk.redactTokens())
                    }
                }
                output.toString()
            }.getOrDefault("")
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

    private suspend fun getRemoteUrl(projectDir: File, remote: String): String? {
        val result = run(projectDir to guestProject, guestProject, "git remote get-url ${shq(remote)} 2>&1")
        return result.output.trim().takeIf { result.success }
    }

    private suspend fun setRemoteUrl(projectDir: File, remote: String, url: String): GitResult =
        run(projectDir to guestProject, guestProject, "git remote set-url ${shq(remote)} ${shq(url)} 2>&1")

    /** Runs [block] with [remote]'s URL temporarily rewritten to embed [token], restoring the
     * original URL afterward regardless of success/failure. Previously a token passed at clone/
     * remote-add time sat permanently embedded in `.git/config` at rest; every push/pull/fetch call
     * now goes through here instead so a token is only ever on disk for the duration of one call. */
    private suspend fun withTemporaryCredentials(
        projectDir: File,
        remote: String,
        token: String?,
        block: suspend () -> GitResult,
    ): GitResult {
        if (token.isNullOrBlank()) return block()
        val originalUrl = getRemoteUrl(projectDir, remote) ?: return block()
        if (!originalUrl.startsWith("https://")) return block() // SSH — nothing to inject
        setRemoteUrl(projectDir, remote, withToken(originalUrl, token))
        return try {
            block()
        } finally {
            setRemoteUrl(projectDir, remote, originalUrl)
        }
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
    suspend fun clone(
        url: String,
        targetDir: File,
        token: String? = null,
        onProgress: (String) -> Unit = {},
    ): Result<Unit> {
        if (targetDir.exists()) return Result.failure(IllegalStateException("${targetDir.name} already exists"))
        val parent = targetDir.parentFile ?: return Result.failure(IllegalStateException("no parent dir for ${targetDir.path}"))
        parent.mkdirs()
        val effectiveUrl = withToken(url, token)
        val result = run(
            parent to guestWorkspace,
            guestWorkspace,
            "git clone --progress ${shq(effectiveUrl)} ${shq(targetDir.name)} 2>&1",
            timeoutSec = 1_800,
            onOutput = onProgress,
        )
        return if (result.success && targetDir.isDirectory) {
            // Strip the token back out of origin's URL right away — git clone otherwise leaves it
            // sitting permanently in the new repo's .git/config, same as any other credentialed op.
            if (token != null) setRemoteUrl(targetDir, "origin", url)
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
        val changed = mutableListOf<GitFileState>()
        result.output.lineSequence().forEach { line ->
            when {
                line.startsWith("# branch.head ") -> branch = line.removePrefix("# branch.head ").trim().takeIf { it != "(detached)" }
                line.startsWith("# branch.ab ") -> {
                    val parts = line.removePrefix("# branch.ab ").trim().split(" ")
                    ahead = parts.getOrNull(0)?.removePrefix("+")?.toIntOrNull() ?: 0
                    behind = parts.getOrNull(1)?.removePrefix("-")?.toIntOrNull() ?: 0
                }
                line.startsWith("1 ") -> {
                    val parts = line.split(' ', limit = 9)
                    val xy = parts.getOrNull(1).orEmpty()
                    val path = parts.getOrNull(8)
                    if (xy.length == 2 && path != null) changed += GitFileState(path, xy[0], xy[1])
                }
                line.startsWith("2 ") -> {
                    val parts = line.split(' ', limit = 10)
                    val xy = parts.getOrNull(1).orEmpty()
                    val path = parts.getOrNull(9)?.substringBefore('\t')
                    if (xy.length == 2 && path != null) changed += GitFileState(path, xy[0], xy[1])
                }
                line.startsWith("u ") -> {
                    val parts = line.split(' ', limit = 11)
                    val xy = parts.getOrNull(1).orEmpty()
                    val path = parts.getOrNull(10)
                    if (xy.length == 2 && path != null) changed += GitFileState(path, xy[0], xy[1])
                }
                line.startsWith("? ") -> changed += GitFileState(line.removePrefix("? "), '?', '?')
            }
        }
        return RepoStatus(branch, ahead, behind, changed)
    }

    suspend fun init(projectDir: File): GitResult =
        run(projectDir to guestProject, guestProject, "git init -b main 2>&1")

    suspend fun stage(projectDir: File, paths: List<String> = emptyList()): GitResult {
        val args = if (paths.isEmpty()) "-A" else "-- " + paths.joinToString(" ") { shq(it) }
        return run(projectDir to guestProject, guestProject, "git add $args 2>&1")
    }

    suspend fun unstage(projectDir: File, paths: List<String> = emptyList()): GitResult {
        val args = if (paths.isEmpty()) "" else " -- " + paths.joinToString(" ") { shq(it) }
        val fallback = if (paths.isEmpty()) "." else "-- " + paths.joinToString(" ") { shq(it) }
        return run(projectDir to guestProject, guestProject, "git reset HEAD$args 2>&1 || git rm -r --cached $fallback 2>&1")
    }

    suspend fun commit(projectDir: File, message: String): GitResult =
        run(projectDir to guestProject, guestProject, "git commit -m ${shq(message)} 2>&1")

    suspend fun identity(projectDir: File): GitIdentity {
        val name = run(projectDir to guestProject, guestProject, "git config --get user.name 2>/dev/null").output.takeIf { it.isNotBlank() }
        val email = run(projectDir to guestProject, guestProject, "git config --get user.email 2>/dev/null").output.takeIf { it.isNotBlank() }
        return GitIdentity(name, email)
    }

    suspend fun setIdentity(projectDir: File, name: String, email: String): GitResult =
        run(
            projectDir to guestProject,
            guestProject,
            "git config user.name ${shq(name)} && git config user.email ${shq(email)} 2>&1",
        )

    suspend fun diff(projectDir: File, staged: Boolean = false, path: String? = null): GitResult {
        val cached = if (staged) " --cached" else ""
        val selected = path?.let { " -- ${shq(it)}" }.orEmpty()
        return run(projectDir to guestProject, guestProject, "git --no-pager diff$cached --no-ext-diff$selected 2>&1")
    }

    suspend fun history(projectDir: File, limit: Int = 50): List<GitCommit> {
        val format = "%H%x1f%h%x1f%an%x1f%ar%x1f%s%x1e"
        val result = run(projectDir to guestProject, guestProject, "git --no-pager log -${limit.coerceIn(1, 200)} --format=${shq(format)} 2>&1")
        if (!result.success) return emptyList()
        return result.output.split('\u001e').mapNotNull { row ->
            val parts = row.trim().split('\u001f')
            if (parts.size < 5) null else GitCommit(parts[0], parts[1], parts[2], parts[3], parts.drop(4).joinToString("\u001f"))
        }
    }

    suspend fun deleteBranch(projectDir: File, branch: String, force: Boolean = false): GitResult =
        run(projectDir to guestProject, guestProject, "git branch ${if (force) "-D" else "-d"} ${shq(branch)} 2>&1")

    suspend fun push(projectDir: File, remote: String? = null, branch: String? = null, token: String? = null): GitResult =
        withTemporaryCredentials(projectDir, remote ?: "origin", token) {
            val args = buildString {
                append("git push")
                if (remote != null) append(" ").append(shq(remote))
                if (branch != null) append(" ").append(shq(branch))
                append(" 2>&1")
            }
            run(projectDir to guestProject, guestProject, args)
        }

    suspend fun pull(projectDir: File, remote: String? = null, branch: String? = null, token: String? = null): GitResult =
        withTemporaryCredentials(projectDir, remote ?: "origin", token) {
            val args = buildString {
                append("git pull")
                if (remote != null) append(" ").append(shq(remote))
                if (branch != null) append(" ").append(shq(branch))
                append(" 2>&1")
            }
            run(projectDir to guestProject, guestProject, args)
        }

    suspend fun fetch(projectDir: File, remote: String? = null, token: String? = null): GitResult =
        withTemporaryCredentials(projectDir, remote ?: "origin", token) {
            val args = buildString {
                append("git fetch")
                if (remote != null) append(" ").append(shq(remote))
                append(" 2>&1")
            }
            run(projectDir to guestProject, guestProject, args)
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

    suspend fun remoteDetails(projectDir: File): List<GitRemote> {
        val result = run(projectDir to guestProject, guestProject, "git remote -v 2>&1")
        if (!result.success) return emptyList()
        val rows = result.output.lineSequence().mapNotNull { line ->
            val match = Regex("^(\\S+)\\s+(.+)\\s+\\((fetch|push)\\)$").find(line) ?: return@mapNotNull null
            Triple(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }.toList()
        return rows.groupBy { it.first }.map { (name, values) ->
            GitRemote(
                name,
                values.firstOrNull { it.third == "fetch" }?.second,
                values.firstOrNull { it.third == "push" }?.second,
            )
        }
    }

    suspend fun removeRemote(projectDir: File, name: String): GitResult =
        run(projectDir to guestProject, guestProject, "git remote remove ${shq(name)} 2>&1")

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
