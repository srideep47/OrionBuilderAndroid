package com.legion.orion.feature.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.database.ProviderEntity

@Composable
fun AgentScreen(vm: AgentViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Build", style = MaterialTheme.typography.headlineSmall)
            ui.projectName?.let {
                Text("· $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        ProviderRow(ui.providers, ui.selectedProvider, ui.model, vm::chooseProvider)

        OutlinedTextField(
            value = ui.task,
            onValueChange = vm::onTask,
            label = { Text("Task for the agent") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::run, enabled = !ui.running) { Text("Run agent") }
            if (ui.running) OutlinedButton(onClick = vm::stop) { Text("Stop") }
        }

        ui.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.items.size) { idx ->
                when (val item = ui.items[idx]) {
                    is AgentItem.Assistant -> AssistantBubble(item.text)
                    is AgentItem.Thinking -> ThinkingBubble(item.text)
                    is AgentItem.Note -> Text(
                        item.text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is AgentItem.Tool -> ToolCard(item, onRollback = { item.snapshot?.let(vm::rollback) })
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    providers: List<ProviderEntity>,
    selected: ProviderEntity?,
    model: String,
    onChoose: (ProviderEntity) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(selected?.label ?: "Select provider")
            if (model.isNotEmpty()) {
                Text(" · $model", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { p ->
                DropdownMenuItem(text = { Text(p.label) }, onClick = { onChoose(p); expanded = false })
            }
        }
    }
}

@Composable
private fun AssistantBubble(text: String) {
    if (text.isBlank()) return
    Card(Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ThinkingBubble(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            if (expanded) "▾ Thinking" else "▸ Thinking",
            modifier = Modifier.clickable { expanded = !expanded },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (expanded) {
            Text(
                text,
                Modifier.fillMaxWidth().padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolCard(tool: AgentItem.Tool, onRollback: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val accent = when (tool.status) {
        "error" -> MaterialTheme.colorScheme.error
        "done" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⛏ ${tool.name}", style = MaterialTheme.typography.titleSmall)
                Text(tool.status, style = MaterialTheme.typography.labelMedium, color = accent)
            }
            if (expanded) {
                Text(
                    tool.args,
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                tool.result?.let {
                    Text(
                        it.take(2000),
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (tool.snapshot != null) {
                TextButton(onClick = onRollback) { Text("Roll back to before this edit") }
            }
        }
    }
}
