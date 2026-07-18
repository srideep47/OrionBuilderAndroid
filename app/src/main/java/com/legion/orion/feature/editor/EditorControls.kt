package com.legion.orion.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.Liquid
import com.legion.orion.core.design.LiquidButton
import com.legion.orion.core.design.LiquidChip
import com.legion.orion.core.design.MonoFamily
import com.legion.orion.core.design.liquidGlass
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher

internal data class EditorSettings(
    val fontSizeSp: Int = 14,
    val tabWidth: Int = 4,
    val wordWrap: Boolean = false,
    val lineNumbers: Boolean = true,
    val showWhitespace: Boolean = false,
    val ligatures: Boolean = true,
    val stickyScroll: Boolean = true,
)

@Composable
internal fun EditorSearchPanel(editor: CodeEditor?, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var regex by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    var resultCount by remember { mutableIntStateOf(0) }
    var currentResult by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    fun options(): EditorSearcher.SearchOptions {
        val type = when {
            regex -> EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
            wholeWord -> EditorSearcher.SearchOptions.TYPE_WHOLE_WORD
            else -> EditorSearcher.SearchOptions.TYPE_NORMAL
        }
        return EditorSearcher.SearchOptions(type, !matchCase)
    }

    fun updateResultPosition() {
        val searcher = editor?.searcher ?: return
        resultCount = runCatching { searcher.matchedPositionCount }.getOrDefault(0)
        currentResult = runCatching { searcher.currentMatchedPositionIndex + 1 }.getOrDefault(0).coerceAtLeast(0)
    }

    DisposableEffect(editor) {
        val receipt = editor?.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ -> updateResultPosition() }
        onDispose {
            receipt?.unsubscribe()
            runCatching { editor?.searcher?.stopSearch() }
        }
    }

    LaunchedEffect(query, matchCase, regex, wholeWord, editor) {
        val searcher = editor?.searcher ?: return@LaunchedEffect
        if (query.isBlank()) {
            runCatching { searcher.stopSearch() }
            resultCount = 0
            currentResult = 0
            error = null
        } else {
            runCatching { searcher.search(query, options()) }
                .onSuccess { error = null }
                .onFailure { error = it.message ?: "Invalid search" }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .liquidGlass(shape = RoundedCornerShape(14.dp), fillTopAlpha = 0.09f)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Outlined.Search, null, tint = Cosmos.Violet3, modifier = Modifier.size(16.dp))
            EditorMiniInput(query, { query = it }, "Find", Modifier.weight(1f))
            Text(
                if (resultCount == 0) "0" else "${currentResult.coerceAtLeast(1)}/$resultCount",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                color = Cosmos.TextMuted,
            )
            MiniAction("Prev") {
                runCatching { editor?.searcher?.gotoPrevious() }
                updateResultPosition()
            }
            MiniAction("Next") {
                runCatching { editor?.searcher?.gotoNext() }
                updateResultPosition()
            }
            Icon(
                Icons.Outlined.Close,
                "Close search",
                tint = Cosmos.TextMuted,
                modifier = Modifier.size(18.dp).clickable(onClick = onClose),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Outlined.FindReplace, null, tint = Cosmos.TextMuted, modifier = Modifier.size(16.dp))
            EditorMiniInput(replacement, { replacement = it }, "Replace", Modifier.weight(1f))
            MiniAction("One") { runCatching { editor?.searcher?.replaceCurrentMatch(replacement) } }
            MiniAction("All") { runCatching { editor?.searcher?.replaceAll(replacement) } }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LiquidChip("Match case", selected = matchCase, onClick = { matchCase = !matchCase })
            LiquidChip("Whole word", selected = wholeWord, onClick = { wholeWord = !wholeWord; if (wholeWord) regex = false })
            LiquidChip("Regex", selected = regex, onClick = { regex = !regex; if (regex) wholeWord = false })
        }
        error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF8A9B)) }
    }
}

@Composable
private fun EditorMiniInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(Liquid.ShapePill)
            .background(Cosmos.GlassFill)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        textStyle = MaterialTheme.typography.bodySmall.copy(color = Cosmos.Text, fontFamily = MonoFamily),
        cursorBrush = SolidColor(Cosmos.Violet),
        singleLine = true,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodySmall, color = Cosmos.TextFaint)
                inner()
            }
        },
    )
}

@Composable
private fun MiniAction(label: String, action: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = Cosmos.Violet3,
        modifier = Modifier
            .clip(Liquid.ShapePill)
            .background(Cosmos.GlassFill)
            .clickable(onClick = action)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
}

@Composable
internal fun EditorNoticeBanner(ui: EditorUiState, vm: EditorViewModel) {
    val message = ui.notice ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x26FFB86B))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, style = MaterialTheme.typography.labelSmall, color = Cosmos.Text, modifier = Modifier.weight(1f))
        if (ui.externalChange) {
            MiniAction("Reload") { vm.reloadCurrent(discardChanges = true) }
            MiniAction("Overwrite") { vm.save(force = true) }
        }
        Icon(Icons.Outlined.Close, "Dismiss", tint = Cosmos.TextMuted, modifier = Modifier.size(16.dp).clickable { vm.clearNotice() })
    }
}

@Composable
internal fun EditorStatusBar(
    ui: EditorUiState,
    language: EditorLanguageSpec,
    line: Int,
    column: Int,
    selectionLength: Int,
    onLanguage: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xE60A0618))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            language.label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = Cosmos.Violet3,
            modifier = Modifier.clickable(onClick = onLanguage),
        )
        Text("Ln $line, Col $column", style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily), color = Cosmos.TextMuted)
        if (selectionLength > 0) Text("$selectionLength selected", style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
        Text(ui.encoding, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextFaint)
        Text(ui.lineEnding, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextFaint)
        Text(EditorViewModel.formatBytes(ui.fileSize), style = MaterialTheme.typography.labelSmall, color = Cosmos.TextFaint)
        if (ui.autoSave) Text("Auto-save", style = MaterialTheme.typography.labelSmall, color = Color(0xFF72E6B1))
        if (ui.externalChange) Text("Disk changed", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB86B))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSheet(
    selected: EditorLanguageSpec,
    onSelect: (EditorLanguageSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xF20A0618),
        dragHandle = null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Language mode", style = MaterialTheme.typography.titleMedium.copy(brush = Cosmos.TitleGradient), fontWeight = FontWeight.Bold)
            Text("Detected from the filename; override it for this tab.", style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                EditorLanguageCatalog.all.forEach { language ->
                    LiquidChip(language.label, selected = language.id == selected.id, onClick = { onSelect(language) })
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorOptionsSheet(
    settings: EditorSettings,
    autoSave: Boolean,
    onSettings: (EditorSettings) -> Unit,
    onAutoSave: (Boolean) -> Unit,
    editor: CodeEditor?,
    onRefresh: () -> Unit,
    onSaveAll: () -> Unit,
    onGoToLine: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xF20A0618),
        dragHandle = null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Editor tools", style = MaterialTheme.typography.titleMedium.copy(brush = Cosmos.TitleGradient), fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                val actions: List<Pair<String, () -> Unit>> = listOf(
                    "Select all" to { editor?.selectAll(); Unit },
                    "Copy" to { editor?.copyText(); Unit },
                    "Cut" to { editor?.cutText(); Unit },
                    "Paste" to { editor?.pasteText(); Unit },
                    "Duplicate" to { editor?.duplicateSelection(false); Unit },
                    "Indent" to { editor?.indentSelection(); Unit },
                    "Unindent" to { editor?.unindentSelection(); Unit },
                )
                actions.forEach { (label, action) -> LiquidChip(label, selected = false, onClick = action) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LiquidChip("Go to line", onClick = onGoToLine, selected = false)
                LiquidChip("Reload", onClick = onRefresh, selected = false)
                LiquidChip("Save all", onClick = onSaveAll, selected = false)
                LiquidChip("Auto-save", onClick = { onAutoSave(!autoSave) }, selected = autoSave)
            }
            Text("Appearance & behavior", style = MaterialTheme.typography.labelLarge, color = Cosmos.Text)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LiquidChip("Wrap lines", onClick = { onSettings(settings.copy(wordWrap = !settings.wordWrap)) }, selected = settings.wordWrap)
                LiquidChip("Line numbers", onClick = { onSettings(settings.copy(lineNumbers = !settings.lineNumbers)) }, selected = settings.lineNumbers)
                LiquidChip("Whitespace", onClick = { onSettings(settings.copy(showWhitespace = !settings.showWhitespace)) }, selected = settings.showWhitespace)
                LiquidChip("Ligatures", onClick = { onSettings(settings.copy(ligatures = !settings.ligatures)) }, selected = settings.ligatures)
                LiquidChip("Sticky scope", onClick = { onSettings(settings.copy(stickyScroll = !settings.stickyScroll)) }, selected = settings.stickyScroll)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Font ${settings.fontSizeSp}sp", style = MaterialTheme.typography.bodySmall, color = Cosmos.Text, modifier = Modifier.weight(1f))
                MiniAction("-") { onSettings(settings.copy(fontSizeSp = (settings.fontSizeSp - 1).coerceAtLeast(10))) }
                MiniAction("+") { onSettings(settings.copy(fontSizeSp = (settings.fontSizeSp + 1).coerceAtMost(28))) }
                Text("Tab ${settings.tabWidth}", style = MaterialTheme.typography.bodySmall, color = Cosmos.Text, modifier = Modifier.padding(start = 8.dp))
                MiniAction("-") { onSettings(settings.copy(tabWidth = (settings.tabWidth - 1).coerceAtLeast(2))) }
                MiniAction("+") { onSettings(settings.copy(tabWidth = (settings.tabWidth + 1).coerceAtMost(8))) }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
internal fun GoToLineDialog(editor: CodeEditor?, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF120B24),
        title = { Text("Go to line", color = Cosmos.Text) },
        text = { EditorMiniInput(value, { value = it.filter(Char::isDigit) }, "Line number", Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(onClick = {
                val line = value.toIntOrNull()?.coerceIn(1, editor?.lineCount ?: 1) ?: return@TextButton
                editor?.jumpToLine(line - 1)
                onDismiss()
            }) { Text("Go", color = Cosmos.Violet3) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Cosmos.TextMuted) } },
    )
}

@Composable
internal fun UnsavedChangesDialog(fileName: String, onSave: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF120B24),
        title = { Text("Unsaved changes", color = Cosmos.Text) },
        text = { Text("Save changes to $fileName before closing?", color = Cosmos.TextMuted) },
        confirmButton = { TextButton(onClick = onSave) { Text("Save", color = Cosmos.Violet3) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Discard", color = Color(0xFFFF8A9B)) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Cosmos.TextMuted) }
            }
        },
    )
}

@Composable
internal fun UnsavedProjectDialog(onSaveAndSwitch: () -> Unit, onDiscardAndSwitch: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF120B24),
        title = { Text("Switch projects?", color = Cosmos.Text) },
        text = { Text("This project has unsaved editor tabs.", color = Cosmos.TextMuted) },
        confirmButton = { TextButton(onClick = onSaveAndSwitch) { Text("Save all & switch", color = Cosmos.Violet3) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscardAndSwitch) { Text("Discard & switch", color = Color(0xFFFF8A9B)) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Cosmos.TextMuted) }
            }
        },
    )
}
