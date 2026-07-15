package com.legion.orion.feature.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.design.BadgeTone
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.CosmosBadge
import com.legion.orion.core.design.LiquidButton
import com.legion.orion.core.design.LiquidChip
import com.legion.orion.core.design.LiquidPanel
import com.legion.orion.core.design.LocalBottomChromeHeight
import com.legion.orion.core.design.MonoFamily
import com.legion.orion.core.design.liquidGlass
import com.legion.orion.feature.editor.EditorScreen
import com.legion.orion.feature.preview.PreviewScreen

/** The developer-tool panels of the unified Code space (see feature/code/CodeScreen.kt). Each maps to
 * one selectable panel; [CodeScreen] owns the tab strip / split-pane layout and drives which is shown.
 * TASKS merges the former "Safety" + "Tools" workspace tabs into one background-tasks surface. */
enum class DevTool(val label: String) {
    FILES("Files"),
    EDITOR("Editor"),
    PREVIEW("Preview"),
    TERMINAL("Terminal"),
    GIT("Git"),
    ENV("Env"),
    TASKS("Tasks"),
}

/** Renders one [DevTool] panel, wiring in the [WorkspaceViewModel] and triggering the per-panel
 * refresh (git status / env / terminal / diagnostics) whenever the shown panel or active project
 * changes — reused by [CodeScreen] in both portrait (tabbed) and landscape (split-pane) layouts. */
@Composable
fun DevToolPanel(
    tool: DevTool,
    vm: WorkspaceViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
    onSwitchToTerminal: (() -> Unit)? = null,
) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val buildJobs by vm.buildJobs.collectAsStateWithLifecycle()
    val server by vm.devServer.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val activeProjectId by vm.activeProjectId.collectAsStateWithLifecycle()
    val git by vm.git.collectAsStateWithLifecycle()
    val terminalOutput by vm.terminalOutput.collectAsStateWithLifecycle()
    val terminalRunning by vm.terminalRunning.collectAsStateWithLifecycle()
    val env by vm.env.collectAsStateWithLifecycle()

    LaunchedEffect(tool, activeProjectId) {
        when (tool) {
            DevTool.GIT -> vm.refreshGitStatus()
            DevTool.ENV -> vm.refreshEnv()
            DevTool.TERMINAL -> vm.startTerminal()
            DevTool.TASKS -> vm.refreshDiagnostics()
            else -> {}
        }
    }

    Box(modifier.fillMaxSize()) {
        when (tool) {
            DevTool.FILES -> FileManagerScreen(
                onOpenTerminalHere = { relativePath ->
                    vm.openTerminalAt(relativePath)
                    onSwitchToTerminal?.invoke()
                },
            )
            DevTool.EDITOR -> EditorScreen()
            DevTool.PREVIEW -> PreviewScreen()
            DevTool.GIT -> GitPanel(
                state = git,
                hasActiveProject = activeProjectId != null,
                runtimeReady = vm.gitRuntimeReady(),
                onClone = vm::cloneRepo,
                onPush = vm::push,
                onPull = vm::pull,
                onCheckout = vm::checkoutBranch,
                onSaveToken = vm::saveGitToken,
                onGenerateSshKey = vm::generateSshKey,
            )
            DevTool.ENV -> EnvPanel(
                state = env,
                onSet = vm::setEnvEntry,
                onRemove = vm::removeEnvEntry,
            )
            DevTool.TERMINAL -> TerminalPanel(
                output = terminalOutput,
                running = terminalRunning,
                runtimeReady = vm.gitRuntimeReady(),
                onSend = vm::sendTerminalCommand,
                onClear = vm::clearTerminal,
                onRestart = vm::restartTerminal,
            )
            DevTool.TASKS -> TasksPanel(
                projectCount = projects.size,
                serverUrl = server?.url,
                buildJobs = buildJobs,
                diagnostics = diagnostics,
                onRefresh = vm::refreshDiagnostics,
                onClear = vm::clearDiagnostics,
            )
        }
    }
}

@Composable
private fun EnvPanel(
    state: EnvPanelState,
    onSet: (key: String, value: String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.hasProject) {
        Column(modifier.fillMaxSize().padding(16.dp)) {
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Text("Open a project first to manage its .env file.", style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
            }
        }
        return
    }

    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    val revealed = remember { mutableStateOf(setOf<String>()) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = LocalBottomChromeHeight.current + 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "A real .env file in the project root — Claude Code's Bash tool, a dev server's " +
                    "process.env, etc. all read it as a plain file, same as on desktop. Values are " +
                    "masked here by default; this doesn't encrypt the file on disk.",
                style = MaterialTheme.typography.labelSmall,
                color = Cosmos.TextMuted,
            )
        }
        items(state.entries, key = { it.key }) { entry ->
            val isRevealed = entry.key in revealed.value
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.key, style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily), color = Cosmos.Text)
                        Text(
                            if (isRevealed) entry.value.ifBlank { "(empty)" } else "•".repeat(entry.value.length.coerceIn(4, 24)),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                            color = Cosmos.TextDim,
                        )
                    }
                    Icon(
                        if (isRevealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        "Toggle visibility",
                        tint = Cosmos.TextMuted,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { revealed.value = if (isRevealed) revealed.value - entry.key else revealed.value + entry.key },
                    )
                    Icon(
                        Icons.Outlined.Delete,
                        "Remove",
                        tint = Cosmos.Red,
                        modifier = Modifier.size(18.dp).clickable { onRemove(entry.key) },
                    )
                }
            }
        }
        item {
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add variable", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                    GitTextField(newKey, { newKey = it.uppercase().replace(' ', '_') }, "KEY")
                    GitTextField(newValue, { newValue = it }, "value", obscure = true)
                    LiquidButton(
                        "Add",
                        compact = true,
                        icon = Icons.Outlined.Add,
                        onClick = {
                            if (newKey.isNotBlank()) {
                                onSet(newKey, newValue)
                                newKey = ""; newValue = ""
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalPanel(
    output: String,
    running: Boolean,
    runtimeReady: Boolean,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!runtimeReady) {
        Column(modifier.fillMaxSize().padding(16.dp)) {
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "The terminal runs inside the Claude Code environment — install it first from the " +
                        "Claude Code mode picker in Chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Cosmos.TextMuted,
                )
            }
        }
        return
    }

    var input by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    LaunchedEffect(output) { scrollState.animateScrollTo(scrollState.maxValue) }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            CosmosBadge(if (running) "Running" else "Stopped", if (running) BadgeTone.Green else BadgeTone.Red)
            Modifier.weight(1f).let { Row(it) {} }
            LiquidChip("Clear", onClick = onClear)
            Row(Modifier.padding(start = 8.dp)) { LiquidChip("Restart", onClick = onRestart) }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .liquidGlass(shape = RoundedCornerShape(12.dp), fillTopAlpha = 0.06f)
                .padding(10.dp)
                .verticalScroll(scrollState),
        ) {
            Text(
                output.ifBlank { "$ " },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                color = Cosmos.TextDim,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .liquidGlass(shape = RoundedCornerShape(12.dp), fillTopAlpha = 0.08f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Cosmos.Text, fontFamily = MonoFamily),
                cursorBrush = SolidColor(Cosmos.Violet),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text("command…", style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily), color = Cosmos.TextFaint)
                    }
                    inner()
                },
            )
            LiquidButton(
                "Run",
                compact = true,
                onClick = {
                    if (input.isNotBlank()) {
                        onSend(input)
                        input = ""
                    }
                },
            )
        }
    }
}

@Composable
private fun GitPanel(
    state: GitPanelState,
    hasActiveProject: Boolean,
    runtimeReady: Boolean,
    onClone: (url: String, tokenHost: String?, name: String?) -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
    onCheckout: (String) -> Unit,
    onSaveToken: (host: String, token: String) -> Unit,
    onGenerateSshKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var cloneUrl by remember { mutableStateOf("") }
    var tokenHost by remember { mutableStateOf("") }
    var tokenValue by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp,
            bottom = LocalBottomChromeHeight.current + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!runtimeReady) {
            item {
                LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Git runs inside the Claude Code environment — install it first from the " +
                            "Claude Code mode picker in Chat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Cosmos.TextMuted,
                    )
                }
            }
            return@LazyColumn
        }

        state.error?.let { err ->
            item {
                LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(err, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily), color = Cosmos.Red)
                }
            }
        }
        state.notice?.let { notice ->
            item {
                Text(notice, style = MaterialTheme.typography.labelSmall, color = Cosmos.Green, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }

        if (hasActiveProject && state.isRepo) {
            item {
                LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.status?.branch ?: "(detached)",
                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFamily),
                                color = Cosmos.Text,
                                modifier = Modifier.weight(1f),
                            )
                            if (state.status != null && (state.status.ahead > 0 || state.status.behind > 0)) {
                                CosmosBadge("↑${state.status.ahead} ↓${state.status.behind}", BadgeTone.Amber)
                            } else {
                                CosmosBadge("up to date", BadgeTone.Green)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LiquidButton("Pull", onClick = onPull, enabled = !state.busy, compact = true)
                            LiquidButton("Push", onClick = onPush, enabled = !state.busy, compact = true)
                        }
                        if (state.status?.isDirty == true) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Changed files", style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
                                state.status.changedFiles.take(20).forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily), color = Cosmos.Amber)
                                }
                            }
                        }
                    }
                }
            }
            if (state.branches.isNotEmpty()) {
                item {
                    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Branches", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                state.branches.forEach { b ->
                                    LiquidChip(b, selected = b == state.status?.branch, onClick = { onCheckout(b) })
                                }
                            }
                        }
                    }
                }
            }
            if (state.remotes.isNotEmpty()) {
                item {
                    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Remotes", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                            state.remotes.forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily), color = Cosmos.TextDim)
                            }
                        }
                    }
                }
            }
        } else {
            item {
                LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Clone a repository", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                        Text(
                            "Clones into a new project. For a private repo, save an access token below first, matching the host (e.g. github.com).",
                            style = MaterialTheme.typography.bodySmall,
                            color = Cosmos.TextMuted,
                        )
                        GitTextField(cloneUrl, { cloneUrl = it }, "https://github.com/owner/repo.git")
                        LiquidButton(
                            if (state.busy) "Cloning…" else "Clone",
                            onClick = {
                                val host = Regex("https://([^/]+)/").find(cloneUrl)?.groupValues?.get(1)
                                onClone(cloneUrl, host, null)
                            },
                            enabled = !state.busy && cloneUrl.isNotBlank(),
                            compact = true,
                        )
                    }
                }
            }
        }

        item {
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("HTTPS access token", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                    Text(
                        "Saved per host, embedded into the remote URL on clone — never shown again after saving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Cosmos.TextMuted,
                    )
                    GitTextField(tokenHost, { tokenHost = it }, "github.com")
                    GitTextField(tokenValue, { tokenValue = it }, "personal access token", obscure = true)
                    LiquidButton(
                        "Save token",
                        compact = true,
                        onClick = {
                            if (tokenHost.isNotBlank() && tokenValue.isNotBlank()) {
                                onSaveToken(tokenHost.trim(), tokenValue.trim())
                                tokenValue = ""
                            }
                        },
                    )
                }
            }
        }

        item {
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SSH key", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text, modifier = Modifier.weight(1f))
                        LiquidButton("Generate", onClick = onGenerateSshKey, enabled = !state.busy, compact = true, icon = Icons.Outlined.Key)
                    }
                    state.sshPublicKey?.let { key ->
                        Text(
                            "Add this to GitHub/GitLab as a new SSH key, then use a git@ URL to clone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Cosmos.TextMuted,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                key,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                                color = Cosmos.TextDim,
                                maxLines = 2,
                                modifier = Modifier.weight(1f),
                            )
                            LiquidButton(
                                "Copy",
                                compact = true,
                                icon = Icons.Outlined.ContentCopy,
                                onClick = { clipboard.setText(AnnotatedString(key)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GitTextField(value: String, onValueChange: (String) -> Unit, placeholder: String, obscure: Boolean = false) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = if (obscure) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(shape = RoundedCornerShape(12.dp), fillTopAlpha = 0.08f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        textStyle = MaterialTheme.typography.bodySmall.copy(color = Cosmos.Text, fontFamily = MonoFamily),
        cursorBrush = SolidColor(Cosmos.Violet),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily), color = Cosmos.TextFaint)
            }
            inner()
        },
    )
}

/** The unified "Tasks" panel: dev-server/project status, build jobs, live-preview QA diagnostics,
 * and the agent safety rails — merges the former separate "Tools" and "Safety" workspace tabs. */
@Composable
private fun TasksPanel(
    projectCount: Int,
    serverUrl: String?,
    buildJobs: List<com.legion.orion.core.database.BuildJobEntity>,
    diagnostics: WorkspaceDiagnostics,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp,
            bottom = LocalBottomChromeHeight.current + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Environment", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                    ToolRow("Preview server", serverUrl ?: "No dev server running.")
                    ToolRow("Projects", "$projectCount project(s) available to the editor and agent.")
                    ToolRow("QA status", "${diagnostics.consoleErrors.size} console error(s), ${diagnostics.failedRequests.size} failed request(s).")
                }
            }
        }
        item {
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Build jobs", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                    if (buildJobs.isEmpty()) {
                        Text("No build jobs yet.", style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
                    }
                }
            }
        }
        items(buildJobs, key = { it.id }) { job ->
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            job.target.uppercase(),
                            color = Cosmos.Violet3,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        CosmosBadge(
                            job.state,
                            when (job.state) {
                                "done" -> BadgeTone.Green
                                "failed" -> BadgeTone.Red
                                "running" -> BadgeTone.Violet
                                else -> BadgeTone.Amber
                            },
                        )
                    }
                    job.logTail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                            color = Cosmos.TextMuted,
                            maxLines = 8,
                        )
                    }
                    job.artifactPath?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = Cosmos.Green)
                    }
                }
            }
        }
        item {
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Preview QA",
                            style = MaterialTheme.typography.titleMedium,
                            color = Cosmos.Text,
                            modifier = Modifier.weight(1f),
                        )
                        val ok = diagnostics.consoleErrors.isEmpty() && diagnostics.failedRequests.isEmpty()
                        CosmosBadge(if (ok) "Clean" else "Needs review", if (ok) BadgeTone.Green else BadgeTone.Amber)
                    }
                    Text(
                        "Browser console errors and failed requests are captured from the live preview and used by the agent QA gate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Cosmos.TextMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiquidChip("Refresh", onClick = onRefresh)
                        LiquidChip("Clear", onClick = onClear)
                    }
                }
            }
        }
        item { SecurityChecklist() }
        item { DiagnosticList("Console errors", diagnostics.consoleErrors) }
        item { DiagnosticList("Failed requests", diagnostics.failedRequests) }
    }
}

@Composable
private fun SecurityChecklist() {
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Agent safety rails", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
            listOf(
                "Project path jail for file tools",
                "Per-project write lock shared by editor and agent",
                "Shadow git snapshot before mutating tool batches",
                "Placeholder-code guard rejects incomplete writes",
                "Media generation always queues to trusted peers",
            ).forEach {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CosmosBadge("On", BadgeTone.Green)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Cosmos.TextDim)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticList(title: String, entries: List<String>) {
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
            if (entries.isEmpty()) {
                Text("No entries.", style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
            } else {
                entries.take(20).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily), color = Cosmos.Amber)
                }
            }
        }
    }
}

@Composable
private fun ToolRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
        Text(value, style = MaterialTheme.typography.bodySmall, color = Cosmos.TextDim)
    }
}
