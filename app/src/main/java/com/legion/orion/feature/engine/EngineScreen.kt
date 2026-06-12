package com.legion.orion.feature.engine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.design.BadgeTone
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.CosmosBadge
import com.legion.orion.core.design.Eyebrow
import com.legion.orion.core.design.GhostButton
import com.legion.orion.core.design.GlassCard
import com.legion.orion.core.design.GlassPanel
import com.legion.orion.core.design.GlowButton
import com.legion.orion.core.design.MonoFamily
import com.legion.orion.core.design.ScreenHeader
import com.legion.orion.core.design.glass
import com.legion.orion.engine.mnn.MnnBackend
import com.legion.orion.inference.EngineState

@Composable
fun EngineScreen(vm: EngineViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val serverRunning by vm.serverRunning.collectAsStateWithLifecycle()
    val localModels by vm.localModels.collectAsStateWithLifecycle()
    val memoryEstimate = vm.memoryEstimate()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(eyebrow = "Inference cockpit", title = "Engine", subtitle = "Load, run, and serve on-device models")

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(engine, ui) }
            item { HardwareCard() }
            item { MemoryCard(memoryEstimate) }
            item { ServerCard(serverRunning, vm.serverPort(), vm::toggleServer) }
            item { ControlCard(ui, engine, localModels, vm) }
            item { SettingsCard(ui.settings, vm::onSettingsChange) }
            if (ui.output.isNotBlank()) {
                item {
                    GlassPanel(modifier = Modifier.fillMaxWidth(), fill = Color(0x40000000)) {
                        Text(
                            ui.output,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                            color = Cosmos.TextDim,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(estimate: EngineMemoryEstimate) {
    val usedByModel = estimate.modelBytes + estimate.kvCacheBytes + estimate.runtimeReserveBytes
    val projected = estimate.projectedFreeBytes
    val pressure = if (estimate.freeRamBytes <= 0) 0f else (usedByModel.toFloat() / estimate.freeRamBytes).coerceIn(0f, 1f)
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("RAM calculator", Modifier.weight(1f))
                CosmosBadge(if (projected > 0) "Fits" else "Tight", if (projected > 0) BadgeTone.Green else BadgeTone.Red)
            }
            MemoryLine("Free RAM now", estimate.freeRamBytes)
            MemoryLine("Model weights", estimate.modelBytes)
            MemoryLine("KV cache estimate", estimate.kvCacheBytes)
            MemoryLine("Runtime reserve", estimate.runtimeReserveBytes)
            LinearProgressIndicator(
                progress = { pressure },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = if (projected > 0) Cosmos.Violet else Cosmos.Red,
                trackColor = Cosmos.GlassFillStrong,
            )
            Text(
                "Projected headroom after load: ${formatBytes(projected)}",
                style = MaterialTheme.typography.labelMedium,
                color = if (projected > 0) Cosmos.Green else Cosmos.Red,
            )
        }
    }
}

@Composable
private fun MemoryLine(label: String, bytes: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted, modifier = Modifier.weight(1f))
        Text(formatBytes(bytes), style = MaterialTheme.typography.labelMedium, color = Cosmos.TextDim)
    }
}

@Composable
private fun StatusCard(engine: EngineState, ui: EngineUiState) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Status", Modifier.weight(1f))
                val tone = when (engine.status) {
                    EngineState.Status.READY -> BadgeTone.Green
                    EngineState.Status.ERROR -> BadgeTone.Red
                    else -> BadgeTone.Amber
                }
                CosmosBadge(engine.status.toString(), tone)
            }
            engine.backend?.let { Text("Backend · ${it.configValue.uppercase()}", color = Cosmos.TextDim, style = MaterialTheme.typography.bodyMedium) }
            engine.modelDir?.let {
                Text(it.substringAfterLast('/'), style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
            }
            if (ui.tokenCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Telemetry("tokens", ui.tokenCount.toString())
                    Telemetry("tok/s", "%.1f".format(ui.tokensPerSecond))
                }
            }
            engine.error?.let { Text(it, color = Cosmos.Red, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun HardwareCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val soc = if (android.os.Build.VERSION.SDK_INT >= 31) {
        android.os.Build.SOC_MODEL.ifBlank { android.os.Build.HARDWARE }
    } else android.os.Build.HARDWARE
    val isQualcomm = (if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MANUFACTURER else android.os.Build.MANUFACTURER)
        .contains("Qualcomm", true) || soc.startsWith("SM", true)
    val ramGb = remember {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        mi.totalMem / 1.0e9
    }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Hardware")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(soc, style = MaterialTheme.typography.titleMedium, color = Cosmos.Text, modifier = Modifier.weight(1f))
                Text("%.1f GB RAM".format(ramGb), style = MaterialTheme.typography.labelMedium, color = Cosmos.Violet3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CosmosBadge("CPU", BadgeTone.Green)
                CosmosBadge("OpenCL", BadgeTone.Green)
                CosmosBadge("Vulkan", BadgeTone.Green)
                CosmosBadge("QNN / NPU", if (isQualcomm) BadgeTone.Green else BadgeTone.Neutral)
            }
            Text(
                "Fallback chain: CPU → OpenCL → Vulkan → QNN",
                style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted,
            )
        }
    }
}

@Composable
private fun Telemetry(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = Cosmos.Violet3)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
    }
}

@Composable
private fun ServerCard(running: Boolean, port: Int?, onToggle: (Boolean) -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("OpenAI-compatible server", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                Text(
                    if (running) "Running${port?.let { " · port $it" } ?: ""}" else "Off · serves the loaded model",
                    style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted,
                )
            }
            Switch(
                checked = running, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = Cosmos.Violet2, checkedThumbColor = Color.White),
            )
        }
    }
}

@Composable
private fun ControlCard(
    ui: EngineUiState,
    engine: EngineState,
    localModels: List<com.legion.orion.core.database.LocalModelEntity>,
    vm: EngineViewModel,
) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (localModels.isNotEmpty()) {
                Eyebrow("Your models — tap to load")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(localModels, key = { it.id }) { m ->
                        Box(
                            Modifier
                                .glass(RoundedCornerShape(10.dp), fill = Color(0x1FA88CFF), border = Color(0x47A88CFF))
                                .clickable { vm.loadLocal(m) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                m.repoId?.substringAfterLast('/') ?: m.id,
                                style = MaterialTheme.typography.labelMedium,
                                color = Cosmos.Violet3,
                            )
                        }
                    }
                }
            }
            EngineField(ui.modelDir, vm::onModelDirChange, "Model directory (contains config.json)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(MnnBackend.CPU, MnnBackend.OPENCL, MnnBackend.VULKAN, MnnBackend.QNN).forEach { b ->
                    BackendChip(b.configValue.uppercase(), ui.backend == b) { vm.onBackendChange(b) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlowButton(
                    text = if (engine.status == EngineState.Status.READY) "Reload" else "Load model",
                    onClick = vm::load,
                    enabled = !ui.busy && ui.modelDir.isNotBlank(),
                )
                if (engine.status == EngineState.Status.READY) {
                    GhostButton(text = "Unload", onClick = vm::unload)
                }
            }
            EngineField(ui.prompt, vm::onPromptChange, "Prompt")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlowButton(text = "Generate", onClick = vm::generate, enabled = engine.status == EngineState.Status.READY && !ui.busy)
                if (ui.busy) CircularProgressIndicator(Modifier.padding(2.dp), color = Cosmos.Violet, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun SettingsCard(s: EngineSettings, onChange: (EngineSettings) -> Unit) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("Generation settings — applied on next load")

            SettingSlider("Threads", s.threadNum.toFloat(), 1f, 8f, 7) { onChange(s.copy(threadNum = it.toInt())) }
            SettingSlider("Context tokens", s.maxAllTokens.toFloat(), 2048f, 32768f, 14, "%.0f") { onChange(s.copy(maxAllTokens = it.toInt())) }
            SettingSlider("Max new tokens", s.maxNewTokens.toFloat(), 256f, 8192f, 31, "%.0f") { onChange(s.copy(maxNewTokens = it.toInt())) }
            SettingSlider("Temperature", s.temperature, 0f, 2f, 40, "%.2f") { onChange(s.copy(temperature = it)) }
            SettingSlider("Top-P", s.topP, 0f, 1f, 100, "%.2f") { onChange(s.copy(topP = it)) }
            SettingSlider("Top-K", s.topK.toFloat(), 1f, 100f, 99) { onChange(s.copy(topK = it.toInt())) }
            SettingSlider("Min-P", s.minP, 0f, 0.5f, 50, "%.2f") { onChange(s.copy(minP = it)) }
            SettingSlider("TFS-Z", s.tfsZ, 0.5f, 2f, 30, "%.2f") { onChange(s.copy(tfsZ = it)) }
            SettingSlider("Typical", s.typical, 0f, 1f, 100, "%.2f") { onChange(s.copy(typical = it)) }
            SettingSlider("Repetition penalty", s.penalty, 1f, 1.3f, 30, "%.2f") { onChange(s.copy(penalty = it)) }
            SettingSlider("N-gram", s.nGram.toFloat(), 0f, 16f, 16) { onChange(s.copy(nGram = it.toInt())) }
            SettingSlider("N-gram factor", s.nGramFactor, 1f, 1.5f, 50, "%.2f") { onChange(s.copy(nGramFactor = it)) }

            ChipRow("Precision", listOf("low", "normal", "high"), s.precision) { onChange(s.copy(precision = it)) }
            ChipRow("Memory", listOf("low", "normal", "high"), s.memory) { onChange(s.copy(memory = it)) }
            ChipRow("Sampler", listOf("greedy", "temperature", "mixed"), s.sampler) { onChange(s.copy(sampler = it)) }
            ChipRow(
                "KV attention compression",
                listOf("off", "int8_qk", "int8_qkv"),
                when (s.attentionMode) {
                    9 -> "int8_qk"
                    10 -> "int8_qkv"
                    else -> "off"
                },
            ) {
                onChange(
                    s.copy(
                        attentionMode = when (it) {
                            "int8_qk" -> 9
                            "int8_qkv" -> 10
                            else -> 8
                        },
                    ),
                )
            }

            ToggleRow("Memory-map weights (mmap)", s.useMmap) { onChange(s.copy(useMmap = it)) }
            ToggleRow("Use cached mmap", s.useCachedMmap) { onChange(s.copy(useCachedMmap = it)) }
            ToggleRow("Disk-backed KV cache", s.kvCacheMmap) { onChange(s.copy(kvCacheMmap = it)) }
            ToggleRow("Reuse KV between turns", s.reuseKv) { onChange(s.copy(reuseKv = it)) }
            ToggleRow("Thinking / reasoning", s.enableThinking) { onChange(s.copy(enableThinking = it)) }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String, value: Float, min: Float, max: Float, steps: Int, fmt: String = "%.0f", onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextDim, modifier = Modifier.weight(1f))
            Text(fmt.format(value), style = MaterialTheme.typography.labelMedium, color = Cosmos.Violet3)
        }
        androidx.compose.material3.Slider(
            value = value, onValueChange = onChange, valueRange = min..max, steps = steps,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Cosmos.Violet, activeTrackColor = Cosmos.Violet2, inactiveTrackColor = Cosmos.GlassFillStrong,
            ),
        )
    }
}

@Composable
private fun ChipRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { opt -> BackendChip(opt.uppercase(), selected == opt) { onSelect(opt) } }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextDim, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Cosmos.Violet2, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun BackendChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .glass(
                RoundedCornerShape(10.dp),
                fill = if (selected) Color(0x33A88CFF) else Cosmos.GlassFill,
                border = if (selected) Color(0x66A88CFF) else Cosmos.Hairline,
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Cosmos.Violet3 else Cosmos.TextMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun EngineField(value: String, onChange: (String) -> Unit, hint: String) {
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(10.dp)),
        placeholder = { Text(hint, color = Cosmos.TextFaint) },
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Cosmos.Violet,
            focusedTextColor = Cosmos.Text,
            unfocusedTextColor = Cosmos.Text,
        ),
    )
}

private fun formatBytes(bytes: Long): String {
    val sign = if (bytes < 0) "-" else ""
    val abs = kotlin.math.abs(bytes.toDouble())
    val gb = abs / 1_073_741_824.0
    val mb = abs / 1_048_576.0
    return when {
        gb >= 1.0 -> "$sign%.2f GB".format(gb)
        mb >= 1.0 -> "$sign%.0f MB".format(mb)
        else -> "$sign${abs.toLong()} B"
    }
}
