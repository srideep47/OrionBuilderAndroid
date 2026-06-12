package com.legion.orion.feature.engine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.engine.mnn.MnnBackend
import com.legion.orion.inference.EngineState

@Composable
fun EngineScreen(vm: EngineViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val serverRunning by vm.serverRunning.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Engine", style = MaterialTheme.typography.headlineMedium)

        StatusCard(engine)

        ServerCard(
            running = serverRunning,
            port = vm.serverPort(),
            onToggle = vm::toggleServer,
        )

        OutlinedTextField(
            value = ui.modelDir,
            onValueChange = vm::onModelDirChange,
            label = { Text("Model directory (contains config.json)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(MnnBackend.CPU, MnnBackend.OPENCL, MnnBackend.VULKAN, MnnBackend.QNN).forEach { b ->
                FilterChip(
                    selected = ui.backend == b,
                    onClick = { vm.onBackendChange(b) },
                    label = { Text(b.configValue.uppercase()) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::load, enabled = !ui.busy && ui.modelDir.isNotBlank()) {
                Text(if (engine.status == EngineState.Status.READY) "Reload" else "Load model")
            }
            OutlinedButton(onClick = vm::unload, enabled = engine.status == EngineState.Status.READY) {
                Text("Unload")
            }
        }

        OutlinedTextField(
            value = ui.prompt,
            onValueChange = vm::onPromptChange,
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = vm::generate,
                enabled = engine.status == EngineState.Status.READY && !ui.busy,
            ) { Text("Generate") }
            if (ui.busy) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        }

        if (ui.tokenCount > 0) {
            Text(
                "%d tokens · %.1f tok/s".format(ui.tokenCount, ui.tokensPerSecond),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        if (ui.output.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    ui.output,
                    modifier = Modifier.padding(16.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ServerCard(running: Boolean, port: Int?, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("OpenAI-compatible API server", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (running) "Running${port?.let { " · port $it" } ?: ""}" else "Off · serves the loaded model",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = running, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun StatusCard(engine: EngineState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status: ${engine.status}", style = MaterialTheme.typography.titleMedium)
            engine.backend?.let { Text("Backend: ${it.configValue.uppercase()}") }
            engine.modelDir?.let {
                Text(
                    "Model: ${it.substringAfterLast('/')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            engine.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
