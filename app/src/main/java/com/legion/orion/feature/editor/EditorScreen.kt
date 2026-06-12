package com.legion.orion.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rosemoe.sora.widget.CodeEditor

@Composable
fun EditorScreen(vm: EditorViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var editorRef by remember { mutableStateOf<CodeEditor?>(null) }
    var loadedPath by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (ui.projectDir == null) {
            Text(
                "No project yet — create one from Build.",
                Modifier.padding(24.dp).align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Box
        }

        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FilePicker(ui.files, ui.openPath, vm::open)
                Button(
                    onClick = {
                        editorRef?.let { vm.onContentChange(it.text.toString()) }
                        vm.save()
                    },
                    enabled = ui.openPath != null,
                ) { Text(if (ui.dirty) "Save*" else "Save") }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> CodeEditor(ctx).apply { editorRef = this } },
                update = { editor ->
                    editorRef = editor
                    // Only reset text when switching files to avoid clobbering in-progress edits.
                    if (loadedPath != ui.openPath) {
                        editor.setText(ui.content)
                        loadedPath = ui.openPath
                    }
                },
            )
        }
    }
}

@Composable
private fun FilePicker(files: List<String>, current: String?, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        TextButton(onClick = { expanded = true }) {
            Text(current ?: "Open file", style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            files.forEach { f ->
                DropdownMenuItem(text = { Text(f) }, onClick = { onPick(f); expanded = false })
            }
        }
    }
}
