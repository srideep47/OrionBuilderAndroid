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
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.database.ProjectEntity
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.Liquid
import com.legion.orion.core.design.LiquidChip
import com.legion.orion.core.design.LiquidPanel
import com.legion.orion.core.design.liquidGlass
import com.legion.orion.feature.chat.ChatContent
import com.legion.orion.feature.chat.ChatMode
import com.legion.orion.feature.chat.ChatViewModel
import com.legion.orion.feature.chat.ClaudeCodeOptionsSheet
import com.legion.orion.feature.chat.CommandHeader
import com.legion.orion.feature.chat.HistorySheet
import com.legion.orion.feature.chat.ModelSheet
import com.legion.orion.feature.workspace.DevTool
import com.legion.orion.feature.workspace.DevToolPanel
import com.legion.orion.feature.workspace.WorkspaceViewModel

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
    TERMINAL("Terminal", DevTool.TERMINAL),
    GIT("Git", DevTool.GIT),
    ENV("Env", DevTool.ENV),
    TASKS("Tasks", DevTool.TASKS),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeScreen(
    chatVm: ChatViewModel = hiltViewModel(),
    workspaceVm: WorkspaceViewModel = hiltViewModel(),
) {
    val ui by chatVm.ui.collectAsStateWithLifecycle()
    val messages by chatVm.messages.collectAsStateWithLifecycle()
    val chats by chatVm.chats.collectAsStateWithLifecycle()
    val engine by chatVm.engineState.collectAsStateWithLifecycle()
    val availableProjects by chatVm.availableProjects.collectAsStateWithLifecycle()

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
    var projectPickerOpen by remember { mutableStateOf(false) }

    val openModelPicker = {
        if (ui.mode == ChatMode.CLAUDE_CODE) {
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
                        )
                    }
                }
            } else {
                PanelStrip(selected = panel, onSelect = { panelOrdinal = it.ordinal })
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (panel == CodePanel.CHAT) {
                        ChatContent(ui = ui, messages = messages, vm = chatVm, modifier = Modifier.fillMaxSize())
                    } else {
                        DevToolPanel(
                            tool = panel.tool!!,
                            vm = workspaceVm,
                            modifier = Modifier.fillMaxSize(),
                            onSwitchToTerminal = { panelOrdinal = CodePanel.TERMINAL.ordinal },
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
                onDismiss = { claudeOptionsSheetOpen = false },
            )
        }
        if (projectPickerOpen) {
            ProjectPickerSheet(
                projects = availableProjects,
                activeProjectName = ui.projectName,
                onNewProject = { chatVm.startFreshProject(); projectPickerOpen = false },
                onSelect = { chatVm.selectProject(it); projectPickerOpen = false },
                onDismiss = { projectPickerOpen = false },
            )
        }
    }
}

/** Lets the user explicitly continue an existing project (resuming its Claude Code session on
 * purpose) or start a brand-new one from scratch, instead of a mode switch silently attaching to
 * whichever project happens to be first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectPickerSheet(
    projects: List<ProjectEntity>,
    activeProjectName: String?,
    onNewProject: () -> Unit,
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

/** Portrait panel switcher: Chat + every dev tool, scrollable so all eight fit on a phone. */
@Composable
private fun PanelStrip(selected: CodePanel, onSelect: (CodePanel) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CodePanel.entries.forEach { p ->
            LiquidChip(p.label, selected = p == selected, onClick = { onSelect(p) })
        }
    }
}

/** Landscape right-pane tool switcher (chat is always the left pane, so it's omitted here). */
@Composable
private fun ToolStrip(tools: List<DevTool>, selected: DevTool, onSelect: (DevTool) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tools.forEach { t ->
            LiquidChip(t.label, selected = t == selected, onClick = { onSelect(t) })
        }
    }
}
