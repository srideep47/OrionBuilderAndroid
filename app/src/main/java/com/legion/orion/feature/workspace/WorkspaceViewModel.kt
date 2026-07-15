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
import com.legion.orion.core.datastore.SecretStore
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

data class GitPanelState(
    val busy: Boolean = false,
    val isRepo: Boolean = false,
    val status: GitRemoteManager.RepoStatus? = null,
    val remotes: List<String> = emptyList(),
    val branches: List<String> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val sshPublicKey: String? = null,
)

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val projectManager: ProjectManager,
    jobsDao: JobsDao,
    devServerState: DevServerState,
    private val previewController: PreviewController,
    private val activeProject: ActiveProjectStore,
    private val secretStore: SecretStore,
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
            val branches = gitManager.listBranches(dir)
            if (requestId == gitRefreshRequestId) {
                _git.value = _git.value.copy(busy = false, isRepo = true, status = status, remotes = remotes, branches = branches)
            }
        }
    }

    /** Clones [url] into a brand-new project and makes it the active project. [tokenHost], if set,
     * looks up a previously-saved PAT for that host (see [saveGitToken]) to embed for a private repo. */
    fun cloneRepo(url: String, tokenHost: String?, name: String? = null) {
        viewModelScope.launch {
            _git.value = _git.value.copy(busy = true, error = null, notice = "Cloning…")
            val token = tokenHost?.let { secretStore.get("git_pat_$it") }
            val id = projectManager.newProjectId()
            val dir = projectManager.dirFor(id)
            gitManager.clone(url, dir, token)
                .onSuccess {
                    val projectName = name?.takeIf { it.isNotBlank() } ?: gitManager.repoNameFromUrl(url)
                    val entity = projectManager.registerProject(id, projectName)
                    activeProject.set(entity.id)
                    _git.value = GitPanelState(notice = "Cloned $projectName")
                    refreshGitStatus()
                }
                .onFailure {
                    _git.value = _git.value.copy(busy = false, notice = null, error = "Clone failed: ${it.message}")
                }
        }
    }

    fun push() {
        val dir = activeProjectDir() ?: return
        viewModelScope.launch {
            _git.value = _git.value.copy(busy = true, error = null, notice = null)
            val result = gitManager.push(dir)
            _git.value = _git.value.copy(
                busy = false,
                notice = if (result.success) "Pushed" else null,
                error = if (result.success) null else result.output.takeLast(400),
            )
            refreshGitStatus()
        }
    }

    fun pull() {
        val dir = activeProjectDir() ?: return
        viewModelScope.launch {
            _git.value = _git.value.copy(busy = true, error = null, notice = null)
            val result = gitManager.pull(dir)
            _git.value = _git.value.copy(
                busy = false,
                notice = if (result.success) "Pulled" else null,
                error = if (result.success) null else result.output.takeLast(400),
            )
            refreshGitStatus()
        }
    }

    fun checkoutBranch(branch: String) {
        val dir = activeProjectDir() ?: return
        viewModelScope.launch {
            _git.value = _git.value.copy(busy = true)
            gitManager.checkout(dir, branch)
            refreshGitStatus()
        }
    }

    fun saveGitToken(host: String, token: String) {
        viewModelScope.launch { secretStore.put("git_pat_$host", token) }
    }

    suspend fun hasGitToken(host: String): Boolean = secretStore.get("git_pat_$host") != null

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
