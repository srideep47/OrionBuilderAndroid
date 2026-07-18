package com.legion.orion.feature.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.database.ProjectEntity
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.Liquid
import com.legion.orion.core.design.LiquidButton
import com.legion.orion.core.design.LiquidChip
import com.legion.orion.core.design.LiquidPanel
import com.legion.orion.core.design.LocalBottomChromeHeight
import com.legion.orion.core.design.MonoFamily
import com.legion.orion.core.design.liquidGlass
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.pow

/**
 * Finder-style file manager: a project list at the top level, then a real file/folder browser inside
 * each project's own directory — rename, delete, new file/folder, multi-select batch delete, cut/copy/
 * paste, copy path, properties, and "open terminal here".
 */
@Composable
fun FileManagerScreen(
    vm: FileManagerViewModel = hiltViewModel(),
    onOpenFile: ((String) -> Unit)? = null,
    onOpenTerminalHere: ((String) -> Unit)? = null,
    hasActiveProjectGit: Boolean = false,
    cloneProgress: CloneProgressState? = null,
    onClone: (url: String, name: String?) -> Unit = { _, _ -> },
    onRetryClone: () -> Unit = {},
    onDismissClone: () -> Unit = {},
    onPush: () -> Unit = {},
    onPull: () -> Unit = {},
    onFetch: () -> Unit = {},
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var newFolderDialog by remember { mutableStateOf(false) }
    var newFileDialog by remember { mutableStateOf(false) }
    var newProjectDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var menuTarget by remember { mutableStateOf<FileEntry?>(null) }
    var propertiesTarget by remember { mutableStateOf<FileEntry?>(null) }
    var gitMenuOpen by remember { mutableStateOf(false) }
    var cloneDialogOpen by remember { mutableStateOf(false) }
    var projectMenuTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    var projectRenameTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    var projectDeleteTarget by remember { mutableStateOf<ProjectEntity?>(null) }

    LaunchedEffect(ui.query) {
        if (ui.query.trim().length >= 2) {
            delay(300)
            vm.searchProject(ui.query)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Breadcrumb bar
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Projects",
                style = MaterialTheme.typography.labelMedium,
                color = if (ui.projectId == null) Cosmos.Text else Cosmos.Violet3,
                fontWeight = if (ui.projectId == null) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { vm.closeProject() },
            )
            if (ui.projectId != null) {
                Icon(Icons.Outlined.ChevronRight, null, tint = Cosmos.TextFaint, modifier = Modifier.size(14.dp))
                Text(
                    ui.projectName ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ui.breadcrumb.isEmpty()) Cosmos.Text else Cosmos.Violet3,
                    fontWeight = if (ui.breadcrumb.isEmpty()) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable { vm.navigateToRoot() },
                )
                ui.breadcrumb.forEachIndexed { index, seg ->
                    Icon(Icons.Outlined.ChevronRight, null, tint = Cosmos.TextFaint, modifier = Modifier.size(14.dp))
                    val isLast = index == ui.breadcrumb.lastIndex
                    Text(
                        seg,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isLast) Cosmos.Text else Cosmos.Violet3,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable { vm.navigateToBreadcrumb(index) },
                    )
                }
            }
        }

        ui.error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Cosmos.Red, modifier = Modifier.padding(horizontal = 16.dp))
        }
        ui.notice?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Cosmos.Green, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (ui.busy) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Cosmos.Violet)
                Text("Updating workspace", style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
            }
        }
        if (ui.projectId != null && cloneProgress != null) {
            CloneProgressRow(
                cloneProgress,
                onRetryClone,
                onDismissClone,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (ui.projectId != null) {
            FileManagerToolbar(
                ui = ui,
                onQuery = vm::setQuery,
                onUp = vm::navigateUp,
                onRefresh = vm::refresh,
                onToggleHidden = vm::toggleHidden,
                onSort = vm::setSort,
            )
        }

        Box(
            Modifier
                .weight(1f)
                // Long-press on empty space (not caught by a list item's own combinedClickable
                // above) opens the git menu — "clone anywhere" always creates a new top-level
                // project regardless of which folder this was opened from (see
                // WorkspaceViewModel.requestClone), so this works the same at the project grid or
                // inside any nested folder.
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = { gitMenuOpen = true },
                ),
        ) {
            if (ui.projectId == null) {
                ProjectGrid(
                    projects = projects,
                    cloneProgress = cloneProgress,
                    onOpen = vm::openProject,
                    onLongPress = { projectMenuTarget = it },
                    onRetryClone = onRetryClone,
                    onDismissClone = onDismissClone,
                )
            } else {
                if (ui.query.trim().length >= 2) {
                    SearchResultList(
                        results = ui.searchResults,
                        busy = ui.searchBusy,
                        onOpen = { onOpenFile?.invoke(it.path) },
                    )
                } else {
                    FileList(
                        entries = ui.entries.filter { ui.query.isBlank() || it.name.contains(ui.query, ignoreCase = true) },
                        selection = ui.selection,
                        onNavigate = vm::navigateInto,
                        onOpenFile = { onOpenFile?.invoke(vm.relativePathFor(it)) },
                        onToggleSelect = vm::toggleSelect,
                        onLongPress = { menuTarget = it },
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = LocalBottomChromeHeight.current + 8.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.projectId == null) {
                LiquidButton("New Project", onClick = { newProjectDialog = true }, compact = true, icon = Icons.Outlined.Add)
                if (ui.hasClipboard) {
                    LiquidButton("Paste", onClick = vm::pasteClipboard, compact = true, icon = Icons.Outlined.ContentPaste)
                }
            } else {
                LiquidButton("Folder", onClick = { newFolderDialog = true }, compact = true, icon = Icons.Outlined.CreateNewFolder)
                LiquidButton("File", onClick = { newFileDialog = true }, compact = true, icon = Icons.Outlined.NoteAdd)
                if (onOpenTerminalHere != null) {
                    LiquidButton("Terminal here", onClick = { onOpenTerminalHere(vm.currentRelativePath()) }, compact = true, icon = Icons.Outlined.Terminal)
                }
                if (ui.hasClipboard) {
                    LiquidButton("Paste", onClick = vm::pasteClipboard, compact = true, icon = Icons.Outlined.ContentPaste)
                }
                if (ui.selection.isNotEmpty()) {
                    LiquidButton("All", onClick = vm::selectAll, compact = true, icon = Icons.Outlined.SelectAll)
                    LiquidButton("Copy", onClick = vm::copySelected, compact = true, icon = Icons.Outlined.ContentCopy)
                    LiquidButton("Cut", onClick = vm::cutSelected, compact = true, icon = Icons.Outlined.ContentCut)
                    LiquidButton("ZIP", onClick = vm::compressSelected, compact = true, icon = Icons.Outlined.Archive)
                    LiquidButton(
                        "Delete (${ui.selection.size})",
                        onClick = vm::deleteSelected,
                        compact = true,
                        icon = Icons.Outlined.Delete,
                    )
                }
            }
        }
    }

    if (newProjectDialog) {
        NamePromptDialog("New project", "project name", onConfirm = { vm.createProject(it); newProjectDialog = false }, onDismiss = { newProjectDialog = false })
    }
    menuTarget?.let { entry ->
        FileContextMenuSheet(
            entry = entry,
            onOpen = if (!entry.isDirectory && onOpenFile != null) {
                { onOpenFile(vm.relativePathFor(entry)); menuTarget = null }
            } else null,
            onRename = { renameTarget = entry; menuTarget = null },
            onDuplicate = { vm.duplicateEntry(entry); menuTarget = null },
            onCompress = { vm.compressEntry(entry); menuTarget = null },
            onExtract = if (!entry.isDirectory && entry.name.endsWith(".zip", true)) {
                { vm.extractZip(entry); menuTarget = null }
            } else null,
            onCopy = { vm.copyEntry(entry); menuTarget = null },
            onCut = { vm.cutEntry(entry); menuTarget = null },
            onCopyPath = { clipboard.setText(AnnotatedString(entry.path)); menuTarget = null },
            onProperties = { propertiesTarget = entry; menuTarget = null },
            onOpenTerminalHere = if (entry.isDirectory && onOpenTerminalHere != null) {
                { onOpenTerminalHere(vm.relativePathFor(entry)); menuTarget = null }
            } else null,
            onDelete = { vm.deleteEntry(entry); menuTarget = null },
            onDismiss = { menuTarget = null },
        )
    }
    propertiesTarget?.let { entry ->
        PropertiesDialog(entry = entry, vm = vm, onDismiss = { propertiesTarget = null })
    }
    if (newFolderDialog) {
        NamePromptDialog("New folder", "folder name", onConfirm = { vm.createFolder(it); newFolderDialog = false }, onDismiss = { newFolderDialog = false })
    }
    if (newFileDialog) {
        NamePromptDialog("New file", "file name", onConfirm = { vm.createFile(it); newFileDialog = false }, onDismiss = { newFileDialog = false })
    }
    renameTarget?.let { entry ->
        NamePromptDialog("Rename", entry.name, initial = entry.name, onConfirm = { vm.rename(entry, it); renameTarget = null }, onDismiss = { renameTarget = null })
    }
    if (gitMenuOpen) {
        FileManagerGitMenuSheet(
            hasActiveProjectGit = hasActiveProjectGit,
            onClone = { gitMenuOpen = false; cloneDialogOpen = true },
            onPush = { gitMenuOpen = false; onPush() },
            onPull = { gitMenuOpen = false; onPull() },
            onFetch = { gitMenuOpen = false; onFetch() },
            onDismiss = { gitMenuOpen = false },
        )
    }
    if (cloneDialogOpen) {
        CloneRepoDialog(onClone = onClone, onDismiss = { cloneDialogOpen = false })
    }
    projectMenuTarget?.let { project ->
        ProjectContextMenuSheet(
            project = project,
            onOpen = { vm.openProject(project); projectMenuTarget = null },
            onRename = { projectRenameTarget = project; projectMenuTarget = null },
            onDuplicate = { vm.duplicateProject(project); projectMenuTarget = null },
            onCopy = { vm.copyProject(project); projectMenuTarget = null },
            onCut = { vm.cutProject(project); projectMenuTarget = null },
            onDelete = { projectDeleteTarget = project; projectMenuTarget = null },
            onDismiss = { projectMenuTarget = null },
        )
    }
    projectRenameTarget?.let { project ->
        NamePromptDialog(
            "Rename project",
            project.name,
            initial = project.name,
            onConfirm = { vm.renameProject(project, it); projectRenameTarget = null },
            onDismiss = { projectRenameTarget = null },
        )
    }
    projectDeleteTarget?.let { project ->
        ConfirmProjectDeleteDialog(
            project = project,
            onConfirm = { vm.deleteProject(project); projectDeleteTarget = null },
            onDismiss = { projectDeleteTarget = null },
        )
    }
}

@Composable
private fun FileManagerToolbar(
    ui: FileManagerUiState,
    onQuery: (String) -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onToggleHidden: () -> Unit,
    onSort: (FileSort) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.06f)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Search, null, tint = Cosmos.TextMuted, modifier = Modifier.size(18.dp))
            BasicTextField(
                value = ui.query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Cosmos.Text, fontFamily = MonoFamily),
                cursorBrush = SolidColor(Cosmos.Violet),
                decorationBox = { inner ->
                    if (ui.query.isBlank()) Text("Search files and contents", color = Cosmos.TextFaint, style = MaterialTheme.typography.bodyMedium)
                    inner()
                },
            )
            if (ui.searchBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Cosmos.Violet)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidChip("Up", icon = Icons.Outlined.ArrowUpward, onClick = onUp)
            LiquidChip("Refresh", icon = Icons.Outlined.Refresh, onClick = onRefresh)
            LiquidChip(
                if (ui.showHidden) "Hidden on" else "Hidden off",
                icon = if (ui.showHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                selected = ui.showHidden,
                onClick = onToggleHidden,
            )
            FileSort.entries.forEach { sort ->
                val suffix = if (ui.sort == sort) if (ui.ascending) " ↑" else " ↓" else ""
                LiquidChip(sort.label + suffix, icon = if (sort == ui.sort) Icons.Outlined.Sort else null, selected = sort == ui.sort, onClick = { onSort(sort) })
            }
        }
    }
}

@Composable
private fun SearchResultList(
    results: List<ProjectSearchResult>,
    busy: Boolean,
    onOpen: (ProjectSearchResult) -> Unit,
) {
    if (results.isEmpty() && !busy) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No project matches", style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextMuted)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(results, key = { "${it.path}:${it.line}:${it.preview}" }) { result ->
            Row(
                Modifier.fillMaxWidth().liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.05f)
                    .clickable { onOpen(result) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.Description, null, tint = Cosmos.Violet3, modifier = Modifier.size(19.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        result.path + (result.line?.let { ":$it" } ?: ""),
                        color = Cosmos.Text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(result.preview, color = Cosmos.TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.OpenInNew, null, tint = Cosmos.TextFaint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ProjectGrid(
    projects: List<ProjectEntity>,
    cloneProgress: CloneProgressState?,
    onOpen: (ProjectEntity) -> Unit,
    onLongPress: (ProjectEntity) -> Unit,
    onRetryClone: () -> Unit,
    onDismissClone: () -> Unit,
) {
    if (projects.isEmpty() && cloneProgress == null) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No projects yet", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
            Text("Create one from Chat -> Build, or clone a repo from the Git tab.", style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (cloneProgress != null) {
            item(key = "active-clone-${cloneProgress.name}") {
                CloneProgressRow(cloneProgress, onRetryClone, onDismissClone, Modifier)
            }
        }
        items(projects, key = { it.id }) { project ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.06f)
                    .combinedClickable(
                        onClick = { onOpen(project) },
                        onLongClick = { onLongPress(project) },
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Folder, null, tint = Cosmos.Violet3, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.bodyMedium, color = Cosmos.Text)
                    Text("${project.stackType} - Long press for folder actions", style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = Cosmos.TextFaint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun CloneProgressRow(
    progress: CloneProgressState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.10f)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (progress.failed) Icons.Outlined.Folder else Icons.Outlined.Folder,
                null,
                tint = if (progress.failed) Cosmos.Red else Cosmos.Violet3,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(progress.name, style = MaterialTheme.typography.bodyMedium, color = Cosmos.Text)
                Text(
                    progress.phase + (progress.percent?.let { " - $it%" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (progress.failed) Cosmos.Red else Cosmos.Violet3,
                )
            }
            if (!progress.failed && progress.percent == null) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Cosmos.Violet)
            }
        }
        progress.percent?.let { percent ->
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = if (progress.failed) Cosmos.Red else Cosmos.Violet,
                trackColor = Cosmos.GlassFillStrong,
            )
        }
        Text(
            progress.detail,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = Cosmos.TextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (progress.failed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidChip("Retry", onClick = onRetry)
                LiquidChip("Dismiss", onClick = onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectContextMenuSheet(
    project: ProjectEntity,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF20A0618),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.Folder, null, tint = Cosmos.Violet3, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                    Text("Project folder", style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
                }
            }
            MenuRow("Open", Icons.Outlined.OpenInNew, onOpen)
            MenuRow("Rename project", Icons.Outlined.DriveFileRenameOutline, onRename)
            MenuRow("Duplicate project", Icons.Outlined.ContentCopy, onDuplicate)
            MenuRow("Copy project folder", Icons.Outlined.ContentCopy, onCopy)
            MenuRow("Cut / move project folder", Icons.Outlined.ContentCut, onCut)
            MenuRow("Delete project", Icons.Outlined.Delete, onDelete, danger = true)
        }
    }
}

@Composable
private fun ConfirmProjectDeleteDialog(
    project: ProjectEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xB0000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.12f)
                .clickable(enabled = false) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Delete project?", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
            Text(
                "This permanently removes ${project.name} and every file in its project folder. This cannot be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = Cosmos.TextMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidChip("Cancel", onClick = onDismiss)
                LiquidButton("Delete", compact = true, onClick = onConfirm, icon = Icons.Outlined.Delete)
            }
        }
    }
}

@Composable
private fun FileList(
    entries: List<FileEntry>,
    selection: Set<String>,
    onNavigate: (FileEntry) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onToggleSelect: (String) -> Unit,
    onLongPress: (FileEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Empty folder", style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextMuted)
        }
        return
    }
    val selecting = selection.isNotEmpty()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(entries, key = { it.path }) { entry ->
            val selected = entry.path in selection
            Row(
                Modifier
                    .fillMaxWidth()
                    .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = if (selected) 0.16f else 0.05f)
                    .combinedClickable(
                        onClick = { if (selecting) onToggleSelect(entry.path) else if (entry.isDirectory) onNavigate(entry) else onOpenFile(entry) },
                        onLongClick = { onLongPress(entry) },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (selecting) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelect(entry.path) })
                } else {
                    fileIcon(entry)
                }
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.bodyMedium, color = Cosmos.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (entry.isDirectory) "Folder" else "${formatSize(entry.sizeBytes)} · ${formatDate(entry.lastModified)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Cosmos.TextMuted,
                    )
                }
                if (!selecting && entry.isDirectory) {
                    Icon(Icons.Outlined.ChevronRight, null, tint = Cosmos.TextFaint, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun fileIcon(entry: FileEntry) {
    val icon: ImageVector = when {
        entry.isDirectory -> Icons.Outlined.Folder
        entry.name.substringAfterLast('.', "").lowercase() in setOf("md", "txt", "json", "yaml", "yml") -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
    Icon(icon, null, tint = if (entry.isDirectory) Cosmos.Violet3 else Cosmos.TextMuted, modifier = Modifier.size(20.dp))
}

/** Long-press context menu for one entry: rename, copy, cut, copy path, properties, open terminal
 * here (directories only, when a terminal host is wired in), delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileContextMenuSheet(
    entry: FileEntry,
    onOpen: (() -> Unit)?,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onCompress: () -> Unit,
    onExtract: (() -> Unit)?,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onCopyPath: () -> Unit,
    onProperties: () -> Unit,
    onOpenTerminalHere: (() -> Unit)?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF20A0618),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                fileIcon(entry)
                Text(entry.name, style = MaterialTheme.typography.titleMedium, color = Cosmos.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onOpen != null) MenuRow("Open in editor", Icons.Outlined.OpenInNew, onOpen)
            MenuRow("Rename", Icons.Outlined.DriveFileRenameOutline, onRename)
            MenuRow("Duplicate", Icons.Outlined.ContentCopy, onDuplicate)
            MenuRow("Copy", Icons.Outlined.ContentCopy, onCopy)
            MenuRow("Cut", Icons.Outlined.ContentCut, onCut)
            MenuRow("Compress to ZIP", Icons.Outlined.Archive, onCompress)
            if (onExtract != null) MenuRow("Extract here", Icons.Outlined.Unarchive, onExtract)
            MenuRow("Copy path", Icons.Outlined.ContentCopy, onCopyPath)
            MenuRow("Properties", Icons.Outlined.Info, onProperties)
            if (onOpenTerminalHere != null) MenuRow("Open terminal here", Icons.Outlined.Terminal, onOpenTerminalHere)
            MenuRow("Delete", Icons.Outlined.Delete, onDelete, danger = true)
        }
    }
}

@Composable
internal fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit, danger: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = if (danger) Cosmos.Red else Cosmos.TextMuted, modifier = Modifier.size(19.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (danger) Cosmos.Red else Cosmos.Text)
    }
}

@Composable
private fun PropertiesDialog(entry: FileEntry, vm: FileManagerViewModel, onDismiss: () -> Unit) {
    var props by remember(entry.path) { mutableStateOf<FileProperties?>(null) }
    LaunchedEffect(entry.path) { props = vm.propertiesFor(entry) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB0000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.12f)
                .clickable(enabled = false) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Properties", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
            val p = props
            if (p == null) {
                Text("Calculating…", style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
            } else {
                PropertyRow("Name", p.name)
                PropertyRow("Type", if (p.isDirectory) "Folder" else "File")
                PropertyRow("Location", p.path.removeSuffix(p.name).removeSuffix("/"))
                PropertyRow("Size", formatSize(p.sizeBytes) + if (p.isDirectory) " (${p.itemCount} item(s))" else "")
                PropertyRow("Modified", formatDate(p.lastModified))
            }
            Spacer(Modifier.height(4.dp))
            LiquidButton("Close", compact = true, onClick = onDismiss)
        }
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted, modifier = Modifier.width(84.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
            color = Cosmos.Text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NamePromptDialog(title: String, placeholder: String, initial: String = "", onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xB0000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.12f)
                .clickable(enabled = false) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.08f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Cosmos.Text, fontFamily = MonoFamily),
                cursorBrush = SolidColor(Cosmos.Violet),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily), color = Cosmos.TextFaint)
                    }
                    inner()
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidChip("Cancel", onClick = onDismiss)
                LiquidButton("OK", compact = true, onClick = { if (text.isNotBlank()) onConfirm(text) })
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    return String.format("%.1f %s", bytes / 1024.0.pow(exp), units[exp - 1])
}

private fun formatDate(millis: Long): String =
    if (millis <= 0) "" else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(millis))
