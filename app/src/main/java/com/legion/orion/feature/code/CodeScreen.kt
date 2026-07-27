package com.legion.orion.feature.code

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.database.ProjectEntity
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.Liquid
import com.legion.orion.core.design.LiquidChip
import com.legion.orion.core.design.LiquidPanel
import com.legion.orion.core.design.WorkspaceTabs
import com.legion.orion.core.design.liquidGlass
import com.legion.orion.feature.chat.ChatContent
import com.legion.orion.feature.chat.ChatMode
import com.legion.orion.feature.chat.ChatViewModel
import com.legion.orion.feature.chat.ClaudeCodeOptionsSheet
import com.legion.orion.feature.chat.CodexOptionsSheet
import com.legion.orion.feature.chat.isAgentCli
import com.legion.orion.feature.chat.CommandHeader
import com.legion.orion.feature.chat.GodotChatCard
import com.legion.orion.feature.chat.HistorySheet
import com.legion.orion.feature.chat.ModelSheet
import com.legion.orion.feature.editor.EditorViewModel
import com.legion.orion.feature.engine.EngineViewModel
import com.legion.orion.feature.workspace.DevTool
import com.legion.orion.feature.workspace.DevToolPanel
import com.legion.orion.feature.workspace.WorkspaceViewModel
import com.legion.orion.claudecode.ClaudePermissionDecision
import com.legion.orion.claudecode.ClaudePermissionRequest

/**
 * The unified Code space — a mobile-optimized Claude Code-style development environment.
 *
 * One surface hosts the whole dev loop: a conversation (with the CHAT / BUILD / CLAUDE_CODE mode
 * toggle, so the same space drives the real Claude Code CLI *or* a local MNN model *or* a Network Hub
 * peer), plus the file browser, code editor, live browser preview, terminal, git, .env, and a
 * background-tasks panel.
 *
 * Responsive layout, matching the desktop Claude Code app scaled to a phone:
 *  - Portrait: a scrollable panel strip switches the single full-width body between Chat and each dev
 *    tool.
 *  - Landscape: a split view — the conversation on the left, a tabbed tool area (files/editor/preview/
 *    terminal/git/env/tasks) on the right — so you can watch the agent work while reading its chat.
 */
private enum class CodePanel(val label: String, val tool: DevTool?) {
    CHAT("Chat", null),
    FILES("Files", DevTool.FILES),
    EDITOR("Editor", DevTool.EDITOR),
    PREVIEW("Preview", DevTool.PREVIEW),
    TOOLS("Tools", null),
}

private val advancedTools = listOf(DevTool.TERMINAL, DevTool.GIT, DevTool.ENV, DevTool.TASKS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeScreen(
    chatVm: ChatViewModel = hiltViewModel(),
    workspaceVm: WorkspaceViewModel = hiltViewModel(),
    editorVm: EditorViewModel = hiltViewModel(),
    engineVm: EngineViewModel = hiltViewModel(),
) {
    val ui by chatVm.ui.collectAsStateWithLifecycle()
    val messages by chatVm.messages.collectAsStateWithLifecycle()
    val chats by chatVm.chats.collectAsStateWithLifecycle()
    val engine by chatVm.engineState.collectAsStateWithLifecycle()
    val availableProjects by chatVm.availableProjects.collectAsStateWithLifecycle()
    val permissionRequests by chatVm.pendingClaudePermissions.collectAsStateWithLifecycle()
    val godotState by engineVm.godotState.collectAsStateWithLifecycle()
    val godotProjectPath by engineVm.godotProjectPath.collectAsStateWithLifecycle()
    val godotPresentation by engineVm.godotPresentation.collectAsStateWithLifecycle()

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Ordinals kept in rememberSaveable so the selected panel survives rotation / process death
    // (enums have no default Saver).
    var panelOrdinal by rememberSaveable { mutableIntStateOf(CodePanel.CHAT.ordinal) }
    var toolOrdinal by rememberSaveable { mutableIntStateOf(DevTool.FILES.ordinal) }
    val panel = CodePanel.entries[panelOrdinal]
    val tool = DevTool.entries[toolOrdinal]

    var drawerOpen by remember { mutableStateOf(false) }
    var modelSheetOpen by remember { mutableStateOf(false) }
    var claudeOptionsSheetOpen by remember { mutableStateOf(false) }
    var codexOptionsSheetOpen by remember { mutableStateOf(false) }
    var projectPickerOpen by remember { mutableStateOf(false) }

    val openModelPicker = {
        if (ui.mode == ChatMode.CODEX) {
            codexOptionsSheetOpen = true
        } else if (ui.mode == ChatMode.CLAUDE_CODE) {
            claudeOptionsSheetOpen = true
        } else {
            ui.selectedProvider?.let(chatVm::loadModelsFor)
            modelSheetOpen = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            CommandHeader(
                ui = ui,
                engine = engine,
                onMenu = { drawerOpen = true },
                onModelPicker = openModelPicker,
                onNewChat = chatVm::newChat,
                onProjectPicker = { projectPickerOpen = true },
            )

            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    ChatContent(
                        ui = ui,
                        messages = messages,
                        vm = chatVm,
                        modifier = Modifier.weight(0.42f).fillMaxHeight(),
                        bottomContent = { imeVisible ->
                            GodotChatCard(
                                state = godotState,
                                projectPath = godotProjectPath,
                                presentation = godotPresentation,
                                onOpenEditor = engineVm::launchGodotEditor,
                                onExpand = engineVm::expandGodot,
                                onReturnToChat = engineVm::returnGodotToChat,
                                onStop = engineVm::stopGodot,
                                previewHeight = 120.dp,
                                temporarilyCollapsed = imeVisible,
                            )
                        },
                    )
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(Cosmos.Hairline),
                    )
                    Column(Modifier.weight(0.58f).fillMaxHeight()) {
                        ToolStrip(
                            tools = DevTool.entries,
                            selected = tool,
                            onSelect = { toolOrdinal = it.ordinal },
                        )
                        DevToolPanel(
                            tool = tool,
                            vm = workspaceVm,
                            modifier = Modifier.weight(1f),
                            onSwitchToTerminal = { toolOrdinal = DevTool.TERMINAL.ordinal },
                            onOpenFileInEditor = { path ->
                                editorVm.open(path)
                                toolOrdinal = DevTool.EDITOR.ordinal
                            },
                        )
                    }
                }
            } else {
                PanelStrip(selected = panel, onSelect = { panelOrdinal = it.ordinal })
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (panel == CodePanel.CHAT) {
                        ChatContent(
                            ui = ui,
                            messages = messages,
                            vm = chatVm,
                            modifier = Modifier.fillMaxSize(),
                            bottomContent = { imeVisible ->
                                GodotChatCard(
                                    state = godotState,
                                    projectPath = godotProjectPath,
                                    presentation = godotPresentation,
                                    onOpenEditor = engineVm::launchGodotEditor,
                                    onExpand = engineVm::expandGodot,
                                    onReturnToChat = engineVm::returnGodotToChat,
                                    onStop = engineVm::stopGodot,
                                    temporarilyCollapsed = imeVisible,
                                )
                            },
                        )
                    } else if (panel == CodePanel.TOOLS) {
                        Column(Modifier.fillMaxSize()) {
                            ToolStrip(
                                tools = advancedTools,
                                selected = if (tool in advancedTools) tool else DevTool.TERMINAL,
                                onSelect = { toolOrdinal = it.ordinal },
                            )
                            DevToolPanel(
                                tool = if (tool in advancedTools) tool else DevTool.TERMINAL,
                                vm = workspaceVm,
                                modifier = Modifier.weight(1f),
                                onSwitchToTerminal = { toolOrdinal = DevTool.TERMINAL.ordinal },
                                onOpenFileInEditor = { path ->
                                    editorVm.open(path)
                                    toolOrdinal = DevTool.EDITOR.ordinal
                                    panelOrdinal = CodePanel.EDITOR.ordinal
                                },
                            )
                        }
                    } else {
                        DevToolPanel(
                            tool = panel.tool!!,
                            vm = workspaceVm,
                            modifier = Modifier.fillMaxSize(),
                            onSwitchToTerminal = {
                                toolOrdinal = DevTool.TERMINAL.ordinal
                                panelOrdinal = CodePanel.TOOLS.ordinal
                            },
                            onOpenFileInEditor = { path ->
                                editorVm.open(path)
                                panelOrdinal = CodePanel.EDITOR.ordinal
                            },
                        )
                    }
                }
            }
        }

        if (drawerOpen) {
            HistorySheet(
                chats = chats,
                activeId = ui.activeChatId,
                onOpen = { chatVm.openChat(it); drawerOpen = false },
                onDelete = chatVm::deleteChat,
                onNew = { chatVm.newChat(); drawerOpen = false },
                onDismiss = { drawerOpen = false },
            )
        }
        if (modelSheetOpen) {
            ModelSheet(
                ui = ui,
                onPick = { provider, model -> chatVm.selectProvider(provider, model); modelSheetOpen = false },
                onProviderFocus = chatVm::loadModelsFor,
                onDismiss = { modelSheetOpen = false },
            )
        }
        if (claudeOptionsSheetOpen) {
            ClaudeCodeOptionsSheet(
                ui = ui,
                onPick = { model, effort, permissionMode -> chatVm.setClaudeCodeOptions(model, effort, permissionMode) },
                onSignIn = chatVm::beginClaudeCodeLogin,
                onSwitchAccount = chatVm::switchClaudeCodeAccount,
                onLogout = chatVm::logoutClaudeCode,
                onSwitchToCodex = { chatVm.switchEngine(ChatMode.CODEX) },
                onDismiss = { claudeOptionsSheetOpen = false },
            )
        }
        if (codexOptionsSheetOpen) {
            CodexOptionsSheet(
                ui = ui,
                onPick = { model, effort, sandbox -> chatVm.setCodexOptions(model, effort, sandbox) },
                onSignIn = chatVm::beginCodexLogin,
                onLogout = chatVm::signOutCodex,
                onReinstall = { chatVm.installCodexRuntime(force = true) },
                onSwitchToClaude = { chatVm.switchEngine(ChatMode.CLAUDE_CODE) },
                onDismiss = { codexOptionsSheetOpen = false },
            )
        }
        if (projectPickerOpen) {
            ProjectPickerSheet(
                projects = availableProjects,
                activeProjectName = ui.projectName,
                showJustChat = ui.mode.isAgentCli,
                onNewProject = { chatVm.startFreshProject(); projectPickerOpen = false },
                onJustChat = { chatVm.selectJustChat(); projectPickerOpen = false },
                onSelect = { chatVm.selectProject(it); projectPickerOpen = false },
                onDismiss = { projectPickerOpen = false },
            )
        }
        permissionRequests.firstOrNull()?.let { request ->
            ClaudePermissionDialog(
                request = request,
                onDecision = { decision -> chatVm.respondToClaudePermission(request.id, decision) },
            )
        }

    }
}

@Composable
private fun ClaudePermissionDialog(
    request: ClaudePermissionRequest,
    onDecision: (ClaudePermissionDecision) -> Unit,
) {
    val detail = when (request.toolName) {
        "Bash" -> request.toolInput["description"]?.toString()?.trim('"')
            ?: request.toolInput["command"]?.toString()?.trim('"')
        "Write", "Edit", "Read" -> request.toolInput["file_path"]?.toString()?.trim('"')
        else -> null
    } ?: request.toolInput.toString()
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Claude needs permission") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    request.toolName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Cosmos.Violet3,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(detail.take(1_600), style = MaterialTheme.typography.bodyMedium, color = Cosmos.Text)
                Text(
                    "Always allow applies only to this Claude session. Orion never silently changes the repository's permission files.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onDecision(ClaudePermissionDecision.ALLOW_ONCE) }) { Text("Allow") }
                TextButton(onClick = { onDecision(ClaudePermissionDecision.ALLOW_SESSION) }) { Text("Always allow") }
            }
        },
        dismissButton = {
            TextButton(onClick = { onDecision(ClaudePermissionDecision.DENY) }) { Text("Don't allow") }
        },
        containerColor = Color(0xFF120B24),
        titleContentColor = Cosmos.Text,
        textContentColor = Cosmos.TextDim,
    )
}

/** Lets the user explicitly continue an existing project (resuming its Claude Code session on
 * purpose) or start a brand-new one from scratch, instead of a mode switch silently attaching to
 * whichever project happens to be first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectPickerSheet(
    projects: List<ProjectEntity>,
    activeProjectName: String?,
    showJustChat: Boolean,
    onNewProject: () -> Unit,
    onJustChat: () -> Unit,
    onSelect: (ProjectEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF20A0618),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Projects",
                style = MaterialTheme.typography.titleMedium.copy(brush = Cosmos.TitleGradient),
                fontWeight = FontWeight.Bold,
            )
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNewProject),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.Add, null, tint = Cosmos.Violet3)
                    Column(Modifier.weight(1f)) {
                        Text("New project", style = MaterialTheme.typography.bodyMedium, color = Cosmos.Text)
                        Text(
                            "Blank directory — Claude Code decides what to create",
                            style = MaterialTheme.typography.labelSmall,
                            color = Cosmos.TextMuted,
                        )
                    }
                }
            }
            if (showJustChat) {
                LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onJustChat),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Outlined.ChatBubbleOutline, null, tint = Cosmos.Violet3)
                        Column(Modifier.weight(1f)) {
                            Text("Just chat with Claude", style = MaterialTheme.typography.bodyMedium, color = Cosmos.Text)
                            Text(
                                "No project bound — plain conversation",
                                style = MaterialTheme.typography.labelSmall,
                                color = Cosmos.TextMuted,
                            )
                        }
                    }
                }
            }
            Text("CONTINUE AN EXISTING PROJECT", style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                items(projects, key = { it.id }) { project ->
                    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(project) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Outlined.Folder, null, tint = Cosmos.TextMuted)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    project.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (project.name == activeProjectName) Cosmos.Violet3 else Cosmos.Text,
                                )
                                Text(project.stackType, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
                            }
                        }
                    }
                }
                if (projects.isEmpty()) {
                    item {
                        Text("No projects yet.", style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Portrait navigation keeps daily work visible and groups lower-frequency operational tools. */
@Composable
private fun PanelStrip(selected: CodePanel, onSelect: (CodePanel) -> Unit) {
    WorkspaceTabs(
        options = CodePanel.entries.map { it.label },
        selectedIndex = selected.ordinal,
        onSelect = { onSelect(CodePanel.entries[it]) },
    )
}

/** Landscape right-pane tool switcher (chat is always the left pane, so it's omitted here). */
@Composable
private fun ToolStrip(tools: List<DevTool>, selected: DevTool, onSelect: (DevTool) -> Unit) {
    WorkspaceTabs(
        options = tools.map { it.label },
        selectedIndex = tools.indexOf(selected).coerceAtLeast(0),
        onSelect = { onSelect(tools[it]) },
    )
}
