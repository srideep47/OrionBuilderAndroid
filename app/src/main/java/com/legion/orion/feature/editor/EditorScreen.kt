package com.legion.orion.feature.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.Liquid
import com.legion.orion.core.design.LiquidButton
import com.legion.orion.core.design.LiquidChip
import com.legion.orion.core.design.LiquidIconButton
import com.legion.orion.core.design.LocalBottomChromeHeight
import com.legion.orion.core.design.MonoFamily
import com.legion.orion.core.design.liquidGlass
import com.legion.orion.core.design.rememberHaptic
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula

/**
 * Mobile code editor: project picker, hierarchical file tree, multi-tab strip,
 * sora-editor with a symbol bar, undo/redo, in-file search, and save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val allProjects by vm.allProjects.collectAsStateWithLifecycle()

    var editorRef by remember { mutableStateOf<CodeEditor?>(null) }
    var loadedPath by remember { mutableStateOf<String?>(null) }
    var loadedRevision by remember { mutableStateOf(-1L) }
    var loadedLanguageId by remember { mutableStateOf<String?>(null) }
    var treeOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var projectSheet by remember { mutableStateOf(false) }
    var newProjectSheet by remember { mutableStateOf(false) }
    var optionsSheet by remember { mutableStateOf(false) }
    var languageSheet by remember { mutableStateOf(false) }
    var goToLineDialog by remember { mutableStateOf(false) }
    var closeCandidate by remember { mutableStateOf<String?>(null) }
    var pendingProjectId by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(EditorSettings()) }
    var cursorLine by remember { mutableStateOf(1) }
    var cursorColumn by remember { mutableStateOf(1) }
    var selectionLength by remember { mutableStateOf(0) }
    val languageOverrides = remember { mutableStateMapOf<String, String>() }
    val detectedLanguage = EditorLanguageCatalog.detect(ui.openPath)
    val automaticLanguageId = if (ui.fileSize > 2_000_000) "plain" else detectedLanguage.id
    val selectedLanguage = EditorLanguageCatalog.byId(ui.openPath?.let(languageOverrides::get) ?: automaticLanguageId)

    // Keep the editor + symbol bar clear of the floating tab bar (keyboard down)
    // or the keyboard (keyboard up), so the last code lines stay visible.
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val bottomPad = if (imeBottom > 0.dp) imeBottom else LocalBottomChromeHeight.current

    Column(Modifier.fillMaxSize().padding(bottom = bottomPad)) {
        // ── Toolbar ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LiquidIconButton(
                if (treeOpen) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                "Files",
                onClick = { treeOpen = !treeOpen },
                active = treeOpen,
                size = 34.dp,
            )

            Column(
                Modifier
                    .weight(1f)
                    .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.08f)
                    .clickable { projectSheet = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    ui.openPath?.substringAfterLast('/') ?: ui.projectName ?: "No project",
                    style = MaterialTheme.typography.labelMedium,
                    color = Cosmos.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    ui.openPath?.substringBeforeLast('/', ui.projectName ?: "Project") ?: "Choose project",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                    color = Cosmos.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            LiquidIconButton(Icons.Outlined.Search, "Find", { searchOpen = !searchOpen }, active = searchOpen, size = 34.dp)
            LiquidIconButton(
                Icons.Outlined.Save,
                "Save",
                onClick = vm::save,
                active = ui.dirty,
                size = 34.dp,
            )
            LiquidIconButton(Icons.Outlined.MoreVert, "Editor tools", { optionsSheet = true }, size = 34.dp)
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidIconButton(Icons.AutoMirrored.Outlined.Undo, "Undo", { editorRef?.takeIf { it.canUndo() }?.undo() }, size = 32.dp)
            LiquidIconButton(Icons.AutoMirrored.Outlined.Redo, "Redo", { editorRef?.takeIf { it.canRedo() }?.redo() }, size = 32.dp)
            LiquidChip(selectedLanguage.label, selected = true, onClick = { languageSheet = true })
            LiquidChip("Go to line", selected = false, onClick = { goToLineDialog = true })
            LiquidChip("Auto-save", selected = ui.autoSave, onClick = { vm.setAutoSave(!ui.autoSave) })
            LiquidIconButton(Icons.Outlined.Refresh, "Refresh from disk", { vm.refresh() }, size = 32.dp)
        }

        // ── Tab strip ──
        if (ui.openTabs.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ui.openTabs.forEach { tab ->
                    val active = tab == ui.openPath
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) Color(0x29A88CFF) else Cosmos.GlassFill)
                            .clickable { vm.open(tab) }
                            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            tab.substringAfterLast('/'),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                            color = if (active) Cosmos.Violet3 else Cosmos.TextMuted,
                            maxLines = 1,
                        )
                        if (tab in ui.dirtyTabs) {
                            Text("*", color = Color(0xFFFFB86B), style = MaterialTheme.typography.labelSmall)
                        }
                        Icon(
                            Icons.Outlined.Close,
                            "Close",
                            tint = Cosmos.TextFaint,
                            modifier = Modifier
                                .size(13.dp)
                                .clickable { if (!vm.closeTab(tab)) closeCandidate = tab },
                        )
                    }
                }
            }
        }

        // ── Search bar ──
        AnimatedVisibility(visible = searchOpen) {
            EditorSearchPanel(editorRef) { searchOpen = false }
        }

        EditorNoticeBanner(ui, vm)

        if (ui.projectDir == null) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No project yet", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Create one from a template, or ask the Build agent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Cosmos.TextMuted,
                )
                Spacer(Modifier.height(16.dp))
                LiquidButton("New project", onClick = { newProjectSheet = true }, icon = Icons.Outlined.Add)
            }
        } else {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedVisibility(visible = treeOpen) {
                    FileTree(
                        files = ui.files,
                        openPath = ui.openPath,
                        onOpen = { vm.open(it); },
                        modifier = Modifier
                            .width(210.dp)
                            .fillMaxHeight()
                            .padding(start = 8.dp, bottom = 8.dp),
                    )
                }

                AndroidView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
                    factory = { ctx ->
                        CodeEditor(ctx).apply {
                            editorRef = this
                            colorScheme = EditorLanguageSupport.colorScheme(ctx).apply {
                                setColor(
                                    io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND,
                                    Color(0xFF0C0818).toArgb(),
                                )
                                setColor(
                                    io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND,
                                    Color(0xFF0C0818).toArgb(),
                                )
                                setColor(
                                    io.github.rosemoe.sora.widget.schemes.EditorColorScheme.CURRENT_LINE,
                                    Color(0x14A88CFF).toArgb(),
                                )
                            }
                            setLineSpacing(2f, 1.1f)
                            textSizePx = settings.fontSizeSp * ctx.resources.displayMetrics.scaledDensity
                            props.apply {
                                autoIndent = true
                                symbolPairAutoCompletion = true
                                highlightMatchingDelimiters = true
                                boldMatchingDelimiters = true
                                enhancedHomeAndEnd = true
                                stickyScroll = settings.stickyScroll
                                actionWhenLineNumberClicked = io.github.rosemoe.sora.widget.DirectAccessProps.LN_ACTION_SELECT_LINE
                            }
                            subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                                when (event.action) {
                                    ContentChangeEvent.ACTION_INSERT -> vm.onInsert(event.changeStart.index, event.changedText)
                                    ContentChangeEvent.ACTION_DELETE -> vm.onDelete(event.changeStart.index, event.changeEnd.index)
                                }
                            }
                            subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
                                cursorLine = cursor.leftLine + 1
                                cursorColumn = cursor.leftColumn + 1
                                selectionLength = (cursor.right - cursor.left).coerceAtLeast(0)
                            }
                        }
                    },
                    update = { editor ->
                        editorRef = editor
                        editor.setWordwrap(settings.wordWrap)
                        editor.setLineNumberEnabled(settings.lineNumbers)
                        editor.setLigatureEnabled(settings.ligatures)
                        editor.setTabWidth(settings.tabWidth)
                        editor.props.stickyScroll = settings.stickyScroll
                        editor.nonPrintablePaintingFlags = if (settings.showWhitespace) {
                            CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
                                CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
                                CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or
                                CodeEditor.FLAG_DRAW_TAB_SAME_AS_SPACE
                        } else {
                            0
                        }
                        editor.textSizePx = settings.fontSizeSp * editor.resources.displayMetrics.scaledDensity
                        if (loadedLanguageId != selectedLanguage.id) {
                            editor.setEditorLanguage(EditorLanguageSupport.create(editor.context, selectedLanguage))
                            loadedLanguageId = selectedLanguage.id
                        }
                        if (loadedPath != ui.openPath || loadedRevision != ui.documentRevision) {
                            editor.setText(ui.content)
                            loadedPath = ui.openPath
                            loadedRevision = ui.documentRevision
                            cursorLine = 1
                            cursorColumn = 1
                            selectionLength = 0
                        }
                    },
                )
            }
        }

        // ── Symbol bar ──
        if (ui.projectDir != null && ui.openPath != null) {
            EditorStatusBar(
                ui = ui,
                language = selectedLanguage,
                line = cursorLine,
                column = cursorColumn,
                selectionLength = selectionLength,
                onLanguage = { languageSheet = true },
            )
            SymbolBar(editorRef)
        }
    }

    if (projectSheet) {
        ModalBottomSheet(
            onDismissRequest = { projectSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xF20A0618),
            dragHandle = null,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Projects",
                        style = MaterialTheme.typography.titleMedium.copy(brush = Cosmos.TitleGradient),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    LiquidButton(
                        "New",
                        compact = true,
                        icon = Icons.Outlined.Add,
                        onClick = { projectSheet = false; newProjectSheet = true },
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(allProjects, key = { it.id }) { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(Liquid.ShapeSmall)
                                .background(if (p.name == ui.projectName) Color(0x1FA88CFF) else Color.Transparent)
                                .clickable {
                                    projectSheet = false
                                    if (vm.hasDirtyDocuments()) pendingProjectId = p.id else vm.selectProject(p.id)
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Outlined.Folder, null, tint = Cosmos.Violet3, modifier = Modifier.size(16.dp))
                            Text(p.name, style = MaterialTheme.typography.bodyMedium, color = Cosmos.Text)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (newProjectSheet) {
        NewProjectSheet(
            templates = vm.scaffolds.templates,
            onCreate = { name, template ->
                vm.createProject(name, template)
                newProjectSheet = false
            },
            onDismiss = { newProjectSheet = false },
        )
    }

    if (languageSheet) {
        LanguageSheet(
            selected = selectedLanguage,
            onSelect = { language ->
                ui.openPath?.let { languageOverrides[it] = language.id }
                languageSheet = false
            },
            onDismiss = { languageSheet = false },
        )
    }

    if (optionsSheet) {
        EditorOptionsSheet(
            settings = settings,
            autoSave = ui.autoSave,
            onSettings = { settings = it },
            onAutoSave = vm::setAutoSave,
            editor = editorRef,
            onRefresh = { vm.refresh(); optionsSheet = false },
            onSaveAll = { vm.saveAll(); optionsSheet = false },
            onGoToLine = { optionsSheet = false; goToLineDialog = true },
            onDismiss = { optionsSheet = false },
        )
    }

    if (goToLineDialog) GoToLineDialog(editorRef) { goToLineDialog = false }

    closeCandidate?.let { path ->
        UnsavedChangesDialog(
            fileName = path.substringAfterLast('/'),
            onSave = {
                if (path == ui.openPath) vm.save()
                vm.closeTab(path, discardDirty = true)
                closeCandidate = null
            },
            onDiscard = { vm.closeTab(path, discardDirty = true); closeCandidate = null },
            onDismiss = { closeCandidate = null },
        )
    }

    pendingProjectId?.let { projectId ->
        UnsavedProjectDialog(
            onSaveAndSwitch = { vm.saveAll(); vm.selectProject(projectId); pendingProjectId = null },
            onDiscardAndSwitch = { vm.selectProject(projectId); pendingProjectId = null },
            onDismiss = { pendingProjectId = null },
        )
    }
}

@Composable
private fun SearchBar(editor: CodeEditor?) {
    var query by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.08f)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Search, null, tint = Cosmos.TextMuted, modifier = Modifier.size(14.dp))
        BasicTextField(
            value = query,
            onValueChange = {
                query = it
                editor?.let { ed ->
                    if (it.isBlank()) {
                        runCatching { ed.searcher.stopSearch() }
                    } else {
                        runCatching {
                            ed.searcher.search(
                                it,
                                EditorSearcher.SearchOptions(true, false),
                            )
                        }
                    }
                }
            },
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodySmall.copy(color = Cosmos.Text, fontFamily = MonoFamily),
            cursorBrush = SolidColor(Cosmos.Violet),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text("Find in file…", style = MaterialTheme.typography.bodySmall, color = Cosmos.TextFaint)
                    }
                    inner()
                }
            },
        )
        Text(
            "next",
            style = MaterialTheme.typography.labelSmall,
            color = Cosmos.Violet3,
            modifier = Modifier
                .clip(Liquid.ShapePill)
                .clickable { runCatching { editor?.searcher?.gotoNext() } }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Text(
            "prev",
            style = MaterialTheme.typography.labelSmall,
            color = Cosmos.TextMuted,
            modifier = Modifier
                .clip(Liquid.ShapePill)
                .clickable { runCatching { editor?.searcher?.gotoPrevious() } }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private val SYMBOLS = listOf("⇥", "{", "}", "(", ")", "[", "]", "<", ">", ";", ":", "\"", "'", "=", "+", "-", "*", "/", "|", "&", "!", "?", ".", ",", "_", "$")

@Composable
private fun SymbolBar(editor: CodeEditor?) {
    val haptic = rememberHaptic()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(Color(0xCC0A0618))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SYMBOLS.forEach { sym ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Cosmos.GlassFill)
                    .clickable {
                        haptic()
                        editor?.let { ed ->
                            val insert = if (sym == "⇥") "  " else sym
                            runCatching {
                                ed.text.insert(ed.cursor.leftLine, ed.cursor.leftColumn, insert)
                            }
                        }
                    }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            ) {
                Text(
                    sym,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
                    color = Cosmos.TextDim,
                )
            }
        }
    }
}

// ─────────────────────────────── File tree ───────────────────────────────

private data class TreeRow(val path: String, val name: String, val depth: Int, val isDir: Boolean)

private fun buildVisibleTree(files: List<String>, expanded: Set<String>): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()
    val dirs = sortedSetOf<String>()
    files.forEach { f ->
        var idx = f.indexOf('/')
        while (idx > 0) {
            dirs += f.substring(0, idx)
            idx = f.indexOf('/', idx + 1)
        }
    }

    fun visible(path: String): Boolean {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            if (parent !in expanded) return false
            parent = parent.substringBeforeLast('/', "")
        }
        return true
    }

    val all = (dirs.map { it to true } + files.map { it to false })
        .sortedWith(compareBy({ it.first.substringBeforeLast('/', "") }, { !it.second }, { it.first }))

    all.forEach { (path, isDir) ->
        if (visible(path)) {
            rows += TreeRow(path, path.substringAfterLast('/'), path.count { it == '/' }, isDir)
        }
    }
    return rows
}

@Composable
private fun FileTree(
    files: List<String>,
    openPath: String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(setOf("src")) }
    val rows = remember(files, expanded) { buildVisibleTree(files, expanded) }
    val haptic = rememberHaptic()

    LazyColumn(
        modifier.liquidGlass(shape = RoundedCornerShape(14.dp), fillTopAlpha = 0.06f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
    ) {
        items(rows, key = { it.path }) { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (row.path == openPath) Color(0x24A88CFF) else Color.Transparent)
                    .clickable {
                        haptic()
                        if (row.isDir) {
                            expanded = if (row.path in expanded) expanded - row.path else expanded + row.path
                        } else {
                            onOpen(row.path)
                        }
                    }
                    .padding(start = (6 + row.depth * 12).dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    when {
                        row.isDir && row.path in expanded -> Icons.Outlined.ExpandMore
                        row.isDir -> Icons.Outlined.KeyboardArrowRight
                        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
                    },
                    null,
                    tint = if (row.isDir) Cosmos.Violet3 else Cosmos.TextMuted,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    row.name,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                    color = if (row.path == openPath) Cosmos.Violet3 else Cosmos.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewProjectSheet(
    templates: List<String>,
    onCreate: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var template by rememberSaveable { mutableStateOf(templates.firstOrNull() ?: "static-web") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xF20A0618),
        dragHandle = null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "New project",
                style = MaterialTheme.typography.titleMedium.copy(brush = Cosmos.TitleGradient),
                fontWeight = FontWeight.Bold,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.08f)
                    .padding(horizontal = 13.dp, vertical = 11.dp),
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Cosmos.Text),
                    cursorBrush = SolidColor(Cosmos.Violet),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (name.isEmpty()) {
                                Text("Project name…", style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextFaint)
                            }
                            inner()
                        }
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                templates.forEach { t ->
                    LiquidChip(t, selected = t == template, onClick = { template = t })
                }
            }
            LiquidButton("Create project", onClick = { onCreate(name, template) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
    }
}
