package com.legion.orion.feature.workspace

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.claudecode.ClaudeCodeToolchain
import com.legion.orion.claudecode.GitRemoteManager
import com.legion.orion.claudecode.TerminalProcess
import com.legion.orion.core.database.BuildJobEntity
import com.legion.orion.core.database.JobsDao
import com.legion.orion.core.database.ProjectEntity
import com.legion.orion.core.datastore.GitAccount
import com.legion.orion.core.datastore.GitAccountStore
import com.legion.orion.core.datastore.SecretStore
import com.legion.orion.data.git.GitHubDeviceAuth
import com.legion.orion.data.project.ActiveProjectStore
import com.legion.orion.data.project.ProjectManager
import com.legion.orion.feature.preview.PreviewController
import com.legion.orion.runtime.DevServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EnvEntry(val key: String, val value: String)

data class EnvPanelState(
    val hasProject: Boolean = false,
    val entries: List<EnvEntry> = emptyList(),
    val notice: String? = null,
)

data class WorkspaceDiagnostics(
    val consoleErrors: List<String> = emptyList(),
    val failedRequests: List<String> = emptyList(),
)

enum class GitPendingActionType { PUSH, PULL, FETCH, CLONE }

/** An account-gated git op waiting on the user's choice from [GitAccountPickerDialog] — only ever
 * populated when 2+ accounts exist; with 0 or 1 the op just runs immediately. */
data class GitPendingAction(
    val type: GitPendingActionType,
    val cloneUrl: String? = null,
    val cloneName: String? = null,
)

/** State for the GitHub OAuth device-flow sign-in banner (see [GitHubDeviceAuth]) — `userCode` /
 * `verificationUri` populate once GitHub issues them, before the user has approved anything. */
data class GitHubLoginState(
    val busy: Boolean = false,
    val userCode: String? = null,
    val verificationUri: String? = null,
    val error: String? = null,
)

data class CloneProgressState(
    val url: String,
    val name: String,
    val phase: String = "Preparing clone",
    val percent: Int? = null,
    val detail: String = "Creating project folder",
    val failed: Boolean = false,
)

internal fun parseCloneProgress(output: String, current: CloneProgressState): CloneProgressState {
    val lines = output.replace('\r', '\n').lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    val latest = lines.lastOrNull() ?: return current
    val progressLine = lines.lastOrNull { line ->
        line.contains("Receiving objects", true) ||
            line.contains("Resolving deltas", true) ||
            line.contains("Updating files", true) ||
            line.contains("Checking out files", true) ||
            line.contains("Compressing objects", true) ||
            line.contains("Counting objects", true) ||
            line.contains("Enumerating objects", true)
    } ?: latest
    val phase = when {
        progressLine.contains("Resolving deltas", true) -> "Resolving deltas"
        progressLine.contains("Updating files", true) -> "Updating files"
        progressLine.contains("Checking out files", true) -> "Checking out files"
        progressLine.contains("Receiving objects", true) -> "Receiving objects"
        progressLine.contains("Compressing objects", true) -> "Compressing objects"
        progressLine.contains("Counting objects", true) -> "Counting objects"
        progressLine.contains("Enumerating objects", true) -> "Enumerating objects"
        progressLine.contains("Cloning into", true) -> "Connecting to remote"
        else -> current.phase
    }
    val percent = Regex("(\\d{1,3})%").findAll(progressLine).lastOrNull()
        ?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
        ?: current.percent.takeIf { phase == current.phase }
    return current.copy(
        phase = phase,
        percent = percent,
        detail = progressLine.removePrefix("remote:").trim().take(180),
        failed = false,
    )
}

data class GitPanelState(
    val busy: Boolean = false,
    val isRepo: Boolean = false,
    val status: GitRemoteManager.RepoStatus? = null,
    val remotes: List<String> = emptyList(),
    val remoteDetails: List<GitRemoteManager.GitRemote> = emptyList(),
    val branches: List<String> = emptyList(),
    val commits: List<GitRemoteManager.GitCommit> = emptyList(),
    val identity: GitRemoteManager.GitIdentity = GitRemoteManager.GitIdentity(null, null),
    val diff: String = "",
    val diffPath: String? = null,
    val diffStaged: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val sshPublicKey: String? = null,
    val pendingAction: GitPendingAction? = null,
    val cloneProgress: CloneProgressState? = null,
)

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val projectManager: ProjectManager,
    jobsDao: JobsDao,
    devServerState: DevServerState,
    private val previewController: PreviewController,
    private val activeProject: ActiveProjectStore,
    private val secretStore: SecretStore,
    private val gitAccountStore: GitAccountStore,
    private val gitHubAuth: GitHubDeviceAuth,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    val projects: StateFlow<List<ProjectEntity>> = projectManager.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val buildJobs: StateFlow<List<BuildJobEntity>> = jobsDao.observeBuildJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val devServer = devServerState.current

    private val _diagnostics = MutableStateFlow(readDiagnostics())
    val diagnostics = _diagnostics.asStateFlow()

    fun refreshDiagnostics() {
        _diagnostics.value = readDiagnostics()
    }

    fun clearDiagnostics() {
        previewController.clearDiagnostics()
        refreshDiagnostics()
    }

    private fun readDiagnostics(): WorkspaceDiagnostics {
        val d = previewController.diagnostics()
        return WorkspaceDiagnostics(d.consoleErrors, d.failedRequests)
    }

    // ── Git ──

    private val claudeCodeToolchain by lazy { ClaudeCodeToolchain(appContext) }
    private val gitManager by lazy { GitRemoteManager(claudeCodeToolchain) }

    private val _git = MutableStateFlow(GitPanelState())
    val git = _git.asStateFlow()

    val activeProjectId: StateFlow<String?> = activeProject.projectId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun activeProjectDir(): File? = activeProject.projectId.value?.let { projectManager.dirFor(it) }

    /** Whether the bundled proot+Ubuntu+git runtime is provisioned (same toolchain Claude Code uses). */
    fun gitRuntimeReady(): Boolean = claudeCodeToolchain.status().provisioned

    private var gitRefreshRequestId = 0

    /** Guards against an overlapping, slower refresh for a previously-active project clobbering a
     * newer refresh's result — each call captures its own request id and only applies its result if
     * still the most recent request when it completes. */
    fun refreshGitStatus() {
        val dir = activeProjectDir() ?: return
        val requestId = ++gitRefreshRequestId
        viewModelScope.launch {
            _git.value = _git.value.copy(busy = true, error = null)
            val isRepo = File(dir, ".git").exists()
            if (!isRepo) {
                if (requestId == gitRefreshRequestId) _git.value = GitPanelState(isRepo = false)
                return@launch
            }
            val status = gitManager.status(dir)
            val remotes = gitManager.remotes(dir)
            val remoteDetails = gitManager.remoteDetails(dir)
            val branches = gitManager.listBranches(dir)
            val commits = gitManager.history(dir)
            val identity = gitManager.identity(dir)
            if (requestId == gitRefreshRequestId) {
                _git.value = _git.value.copy(
                    busy = false,
                    isRepo = true,
                    status = status,
                    remotes = remotes,
                    remoteDetails = remoteDetails,
                    branches = branches,
                    commits = commits,
                    identity = identity,
                )
            }
        }
    }

    fun initRepository() = runGitAction("Repository initialized") { gitManager.init(it) }
    fun stageAll() = runGitAction("Staged all changes") { gitManager.stage(it) }
    fun unstageAll() = runGitAction("Unstaged all changes") { gitManager.unstage(it) }
    fun stageFile(path: String) = runGitAction("Staged $path") { gitManager.stage(it, listOf(path)) }
    fun unstageFile(path: String) = runGitAction("Unstaged $path") { gitManager.unstage(it, listOf(path)) }

    fun commitChanges(message: String, authorName: String, authorEmail: String) {
        val dir = activeProjectDir() ?: return
        if (message.isBlank() || authorName.isBlank() || authorEmail.isBlank()) {
            _git.value = _git.value.copy(error = "Commit message, author name and email are required.")
            return
        }
        viewModelScope.launch {
            _git.value = _git.value.copy(busy = true, error = null, notice = null)
            val identity = gitManager.setIdentity(dir, authorName.trim(), authorEmail.trim())
            val result = if (identity.success) gitManager.commit(dir, message.trim()) else identity
            _git.value = _git.value.copy(
                busy = false,
                notice = if (result.success) "Commit created" else null,
                error = if (result.success) null else result.output.takeLast(1200),
            )
            refreshGitStatus()
        }
    }

    fun showDiff(path: String? = null, staged: Boolean = false) {
        val dir = activeProjectDir() ?: return
        viewModelScope.launch {
            _git.value = _git.value.copy(busy = true, error = null)
            val result = gitManager.diff(dir, staged, path)
            _git.value = _git.value.copy(
                busy = false,
                diff = if (result.success) result.output.ifBlank { "No diff for this selection." } else "",
                diffPath = path,
                diffStaged = staged,
                error = if (result.success) null else result.output.takeLast(1200),
            )
        }
    }

    fun clearDiff() { _git.value = _git.value.copy(diff = "", diffPath = null) }

    fun createBranch(name: String) {
        val dir = activeProjectDir() ?: return
        if (name.isBlank()) return
        runGitAction("Created branch ${name.trim()}") { gitManager.checkout(it, name.trim(), create = true) }
    }

    fun deleteBranch(name: String) {
        val current = _git.value.status?.branch
        if (name.isBlank() || name == current) {
            _git.value = _git.value.copy(error = "Switch away from a branch before deleting it.")
            return
        }
        runGitAction("Deleted branch $name") { gitManager.deleteBranch(it, name) }
    }

    fun addRemote(name: String, url: String) {
        if (name.isBlank() || url.isBlank()) return
        runGitAction("Saved remote ${name.trim()}") { gitManager.addRemote(it, name.trim(), url.trim()) }
    }

    fun removeRemote(name: String) = runGitAction("Removed remote $name") { gitManager.removeRemote(it, name) }

    private fun runGitAction(notice: String, action: suspend (File) -> GitRemoteManager.GitResult) {
        val dir = activeProjectDir() ?: return
        viewModelScope.launch {
            _git.value = _git.value.copy(busy = true, error = null, notice = null)
            val result = action(dir)
            _git.value = _git.value.copy(
                busy = false,
                notice = if (result.success) notice else null,
                error = if (result.success) null else result.output.takeLast(1200),
            )
            refreshGitStatus()
        }
    }

    // ── Git accounts (named HTTPS-PAT profiles) ──

    val gitAccounts: StateFlow<List<GitAccount>> = gitAccountStore.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addGitAccount(label: String, host: String, username: String?, pat: String) {
        viewModelScope.launch {
            val account = gitAccountStore.add(label, host, username)
            secretStore.put("git_pat_account_${account.id}", pat)
        }
    }

    fun removeGitAccount(id: String) {
        viewModelScope.launch {
            gitAccountStore.remove(id)
            secretStore.remove("git_pat_account_$id")
        }
    }

    private suspend fun tokenFor(accountId: String): String? = secretStore.get("git_pat_account_$accountId")

    // ── GitHub "sign in" (OAuth device flow) — alternative to pasting a PAT by hand ──

    private val _githubLogin = MutableStateFlow(GitHubLoginState())
    val githubLogin: StateFlow<GitHubLoginState> = _githubLogin.asStateFlow()

    fun beginGitHubLogin() {
        viewModelScope.launch {
            _githubLogin.value = GitHubLoginState(busy = true)
            gitHubAuth.beginLogin(
                onCode = { code ->
                    _githubLogin.value = GitHubLoginState(
                        busy = true,
                        userCode = code.userCode,
                        verificationUri = code.verificationUri,
                    )
                },
            ).onSuccess { identity ->
                val account = gitAccountStore.add(
                    label = identity.username ?: "GitHub",
                    host = "github.com",
                    username = identity.username,
                )
                secretStore.put("git_pat_account_${account.id}", identity.accessToken)
                _githubLogin.value = GitHubLoginState()
            }.onFailure {
                _githubLogin.value = GitHubLoginState(error = it.message ?: "GitHub sign-in failed")
            }
        }
    }

    fun dismissGitHubLogin() {
        _githubLogin.value = GitHubLoginState()
    }

    /** Runs [action] immediately with 0 or 1 accounts (no prompt needed); with 2+, stashes it as a
     * [GitPendingAction] for [GitAccountPickerDialog] to resolve via [resolvePendingAction] instead. */
    private fun withAccountGate(type: GitPendingActionType, action: suspend (token: String?) -> Unit) {
        viewModelScope.launch {
            val accounts = gitAccountStore.accounts.first()
            if (accounts.size >= 2) {
                _git.value = _git.value.copy(pendingAction = GitPendingAction(type))
            } else {
                action(accounts.firstOrNull()?.let { tokenFor(it.id) })
            }
        }
    }

    fun requestPush() = withAccountGate(GitPendingActionType.PUSH) { doPush(it) }
    fun requestPull() = withAccountGate(GitPendingActionType.PULL) { doPull(it) }
    fun requestFetch() = withAccountGate(GitPendingActionType.FETCH) { doFetch(it) }

    /** Clones [url] into a brand-new top-level project and makes it the active project — always a
     * new top-level project regardless of where the clone was requested from (e.g. a long-press
     * inside a nested folder in the file manager), since every git-aware tool keys off a registered
     * top-level project id; there is no true nested-clone concept here. */
    fun requestClone(url: String, name: String? = null) {
        if (_git.value.cloneProgress?.failed == false) {
            _git.value = _git.value.copy(notice = "A repository is already cloning")
            return
        }
        viewModelScope.launch {
            val accounts = gitAccountStore.accounts.first()
            if (accounts.size >= 2) {
                _git.value = _git.value.copy(pendingAction = GitPendingAction(GitPendingActionType.CLONE, url, name))
            } else {
                doClone(url, name, accounts.firstOrNull()?.let { tokenFor(it.id) })
            }
        }
    }

    /** User's account choice from [GitAccountPickerDialog] for whichever op is in [GitPanelState.pendingAction]. */
    fun resolvePendingAction(accountId: String) {
        val pending = _git.value.pendingAction ?: return
        _git.value = _git.value.copy(pendingAction = null)
        viewModelScope.launch {
            val token = tokenFor(accountId)
            when (pending.type) {
                GitPendingActionType.PUSH -> doPush(token)
                GitPendingActionType.PULL -> doPull(token)
                GitPendingActionType.FETCH -> doFetch(token)
                GitPendingActionType.CLONE -> doClone(pending.cloneUrl!!, pending.cloneName, token)
            }
        }
    }

    fun cancelPendingAction() {
        _git.value = _git.value.copy(pendingAction = null)
    }

    private suspend fun doClone(url: String, name: String?, token: String?) {
        val projectName = name?.trim()?.takeIf { it.isNotBlank() } ?: gitManager.repoNameFromUrl(url)
        _git.value = _git.value.copy(busy = true, error = null, notice = "Cloning…")
        val id = projectManager.newProjectId()
        val dir = projectManager.dirFor(id)
        _git.value = _git.value.copy(
            busy = true,
            error = null,
            notice = null,
            cloneProgress = CloneProgressState(url = url, name = projectName),
        )
        var progressTail = ""
        gitManager.clone(url, dir, token) { chunk ->
            progressTail = (progressTail + chunk).takeLast(8_192)
            val current = _git.value.cloneProgress ?: return@clone
            _git.value = _git.value.copy(cloneProgress = parseCloneProgress(progressTail, current))
        }
            .onSuccess {
                val entity = projectManager.registerProject(id, projectName)
                activeProject.set(entity.id)
                _git.value = GitPanelState(notice = "Cloned $projectName")
                refreshGitStatus()
            }
            .onFailure {
                val message = it.message.orEmpty().lineSequence().lastOrNull { line -> line.isNotBlank() }
                    ?.take(240) ?: "The repository could not be cloned"
                _git.value = _git.value.copy(
                    busy = false,
                    notice = null,
                    error = null,
                    cloneProgress = CloneProgressState(
                        url = url,
                        name = projectName,
                        phase = "Clone failed",
                        detail = message,
                        failed = true,
                    ),
                )
            }
    }

    fun retryClone() {
        val progress = _git.value.cloneProgress?.takeIf { it.failed } ?: return
        requestClone(progress.url, progress.name)
    }

    fun dismissCloneProgress() {
        _git.value = _git.value.copy(cloneProgress = null)
    }

    private suspend fun doPush(token: String?) {
        val dir = activeProjectDir() ?: return
        _git.value = _git.value.copy(busy = true, error = null, notice = null)
        val result = gitManager.push(dir, token = token)
        _git.value = _git.value.copy(
            busy = false,
            notice = if (result.success) "Pushed" else null,
            error = if (result.success) null else result.output.takeLast(400),
        )
        refreshGitStatus()
    }

    private suspend fun doPull(token: String?) {
        val dir = activeProjectDir() ?: return
        _git.value = _git.value.copy(busy = true, error = null, notice = null)
        val result = gitManager.pull(dir, token = token)
        _git.value = _git.value.copy(
            busy = false,
            notice = if (result.success) "Pulled" else null,
            error = if (result.success) null else result.output.takeLast(400),
        )
        refreshGitStatus()
    }

    private suspend fun doFetch(token: String?) {
        val dir = activeProjectDir() ?: return
        _git.value = _git.value.copy(busy = true, error = null, notice = null)
        val result = gitManager.fetch(dir, token = token)
        _git.value = _git.value.copy(
            busy = false,
            notice = if (result.success) "Fetched" else null,
            error = if (result.success) null else result.output.takeLast(400),
        )
        refreshGitStatus()
    }

    fun checkoutBranch(branch: String) {
        runGitAction("Checked out $branch") { gitManager.checkout(it, branch) }
    }

    fun generateSshKey() {
        viewModelScope.launch {
            _git.value = _git.value.copy(busy = true, error = null)
            gitManager.ensureSshKey()
                .onSuccess { key -> _git.value = _git.value.copy(busy = false, sshPublicKey = key) }
                .onFailure { _git.value = _git.value.copy(busy = false, error = "SSH key generation failed: ${it.message}") }
        }
    }

    // ── Terminal ──

    private var terminalProcess: TerminalProcess? = null
    private var terminalProjectId: String? = null
    private var terminalCollectors: List<kotlinx.coroutines.Job> = emptyList()

    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()

    private val _terminalRunning = MutableStateFlow(false)
    val terminalRunning: StateFlow<Boolean> = _terminalRunning.asStateFlow()

    /** Starts (or reuses) a persistent shell bound to the active project dir. Restarts if the active
     * project changed since the last start, so the shell's cwd always matches what's on screen. */
    fun startTerminal() {
        if (!claudeCodeToolchain.status().provisioned) {
            _terminalRunning.value = false
            _terminalOutput.value = "Claude Code environment is not installed. Install it from the Chat mode picker first."
            return
        }
        val pid = activeProject.projectId.value
        if (terminalProcess != null && terminalProjectId == pid && terminalProcess?.running?.value == true) return
        terminalProcess?.stop()
        terminalCollectors.forEach { it.cancel() }
        val dir = pid?.let { projectManager.dirFor(it) }
        val terminal = TerminalProcess(claudeCodeToolchain, dir)
        terminalProcess = terminal
        terminalProjectId = pid
        terminalCollectors = listOf(
            viewModelScope.launch { terminal.output.collect { _terminalOutput.value = it } },
            viewModelScope.launch { terminal.running.collect { _terminalRunning.value = it } },
        )
        terminal.start()
    }

    fun sendTerminalCommand(command: String) {
        viewModelScope.launch { terminalProcess?.send(command) }
    }

    /** Ensures the terminal is running for the active project, then `cd`s into [relativePath]
     * (relative to the project root; blank for the root itself) — the file manager's "open terminal
     * here" action, like Windows' "Open in Terminal" on a folder. [relativePath] is single-quoted
     * before being sent since it goes straight to the shell's stdin exactly like anything the user
     * types — the same class of risk [com.legion.orion.claudecode.GitRemoteManager]'s `shq()` guards
     * against and for the same reason: a folder name (e.g. from a cloned repo) isn't necessarily safe
     * to splice into a command unescaped. */
    fun openTerminalAt(relativePath: String) {
        startTerminal()
        if (relativePath.isNotBlank()) sendTerminalCommand("cd " + shq(relativePath))
    }

    private fun shq(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun clearTerminal() {
        terminalProcess?.clear()
    }

    fun restartTerminal() {
        terminalProcess?.stop()
        startTerminal()
    }

    override fun onCleared() {
        super.onCleared()
        terminalProcess?.stop()
        terminalCollectors.forEach { it.cancel() }
    }

    // ── .env ──

    private val _env = MutableStateFlow(EnvPanelState())
    val env = _env.asStateFlow()

    private fun envFile(): File? = activeProjectDir()?.let { File(it, ".env") }

    /** Real, plaintext `.env` file on disk — Claude Code's own Bash tool, a dev server's
     * `process.env`, python-dotenv, etc. all need to read it as an actual file, same as on desktop;
     * this only adds a friendlier masked-by-default key/value editing UI over that file, it does not
     * (and structurally cannot, without breaking the dev tools that consume it) encrypt it at rest. */
    fun refreshEnv() {
        val file = envFile()
        if (file == null) {
            _env.value = EnvPanelState(hasProject = false)
            return
        }
        val entries = if (file.exists()) parseEnvFile(file.readText()) else emptyList()
        _env.value = EnvPanelState(hasProject = true, entries = entries)
    }

    fun setEnvEntry(key: String, value: String) {
        val cleanKey = key.trim().takeIf { it.isNotBlank() } ?: return
        val current = _env.value.entries.filterNot { it.key == cleanKey }
        _env.value = _env.value.copy(entries = current + EnvEntry(cleanKey, value))
        writeEnvFile()
    }

    fun removeEnvEntry(key: String) {
        _env.value = _env.value.copy(entries = _env.value.entries.filterNot { it.key == key })
        writeEnvFile()
    }

    private fun writeEnvFile() {
        val file = envFile() ?: return
        viewModelScope.launch {
            runCatching {
                file.writeText(_env.value.entries.joinToString("\n") { "${it.key}=${quoteEnvValue(it.value)}" } + "\n")
            }.onFailure { _env.value = _env.value.copy(notice = "Save failed: ${it.message}") }
        }
    }

    /** Double-quotes (with `\`/`"` escaped) any value a real dotenv parser (node's dotenv,
     * python-dotenv, a shell `set -a; . .env`) would otherwise misread — a bare `#` starts an inline
     * comment there, and leading/trailing spaces or literal quote characters are ambiguous unquoted. */
    private fun quoteEnvValue(value: String): String {
        val needsQuoting = value.isEmpty() ||
            value.any { it == '#' || it == ' ' || it == '"' || it == '\n' } ||
            value != value.trim()
        if (!needsQuoting) return value
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun parseEnvFile(text: String): List<EnvEntry> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val key = line.substringBefore('=').trim()
            var value = line.substringAfter('=').trim()
            value = when {
                value.length >= 2 && value.startsWith('"') && value.endsWith('"') ->
                    value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') ->
                    value.substring(1, value.length - 1)
                else -> value
            }
            EnvEntry(key, value)
        }
        .toList()
}
