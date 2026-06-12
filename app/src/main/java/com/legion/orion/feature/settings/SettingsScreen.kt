package com.legion.orion.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            com.legion.orion.core.design.GradientTitle("Settings")
        }
        item { GeneralCard() }
        item {
            CompactionCard(
                enabled = ui.compactionEnabled,
                threshold = ui.compactionThresholdPercent,
                keepRecent = ui.compactionKeepRecentTurns,
                notify = ui.compactionNotify,
                onEnabled = vm::setCompactionEnabled,
                onThreshold = vm::setCompactionThresholdPercent,
                onKeepRecent = vm::setCompactionKeepRecentTurns,
                onNotify = vm::setCompactionNotify,
            )
        }
        item {
            com.legion.orion.core.design.Eyebrow("Model Providers", Modifier.padding(top = 4.dp))
            Text(
                "API keys are encrypted with the Android Keystore and never stored in plaintext.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        items(ui.rows, key = { it.provider.id }) { row ->
            ProviderCard(
                label = row.provider.label,
                hasKey = row.hasKey,
                needsBaseUrl = row.needsBaseUrl,
                baseUrl = row.provider.baseUrl.orEmpty(),
                onSaveKey = { vm.saveKey(row.provider, it) },
                onSaveBaseUrl = { vm.saveBaseUrl(row.provider, it) },
            )
        }
    }
}

@Composable
private fun CompactionCard(
    enabled: Boolean,
    threshold: Int,
    keepRecent: Int,
    notify: Boolean,
    onEnabled: (Boolean) -> Unit,
    onThreshold: (Int) -> Unit,
    onKeepRecent: (Int) -> Unit,
    onNotify: (Boolean) -> Unit,
) {
    com.legion.orion.core.design.GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            com.legion.orion.core.design.Eyebrow("Context Compaction")
            ToggleSetting("Auto compact", enabled, onEnabled)
            SliderSetting(
                label = "Compact at",
                value = threshold,
                suffix = "%",
                range = 50f..95f,
                steps = 8,
                onChange = onThreshold,
            )
            SliderSetting(
                label = "Keep recent turns",
                value = keepRecent,
                suffix = "",
                range = 2f..20f,
                steps = 17,
                onChange = onKeepRecent,
            )
            ToggleSetting("Show compaction notices", notify, onNotify)
            Text(
                "Older turns are summarized into durable chat memory while recent messages stay verbatim.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleSetting(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Int,
    suffix: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Int) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("$value$suffix", style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun GeneralCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }
    com.legion.orion.core.design.GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            com.legion.orion.core.design.Eyebrow("General")
            SettingRow("Appearance", "Cosmos · Dark") {
                com.legion.orion.core.design.CosmosBadge("Dark", com.legion.orion.core.design.BadgeTone.Violet)
            }
            SettingRow("App version", "Orion Builder Android") {
                com.legion.orion.core.design.CosmosBadge("v$version", com.legion.orion.core.design.BadgeTone.Neutral)
            }
            SettingRow("Target", "arm64-v8a · minSdk 29") {
                com.legion.orion.core.design.CosmosBadge("MNN", com.legion.orion.core.design.BadgeTone.Green)
            }
            Text(
                "Network & battery settings live in the Hub tab. YouTube publishing & media live in the Media tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingRow(label: String, sub: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun ProviderCard(
    label: String,
    hasKey: Boolean,
    needsBaseUrl: Boolean,
    baseUrl: String,
    onSaveKey: (String) -> Unit,
    onSaveBaseUrl: (String) -> Unit,
) {
    var key by rememberSaveable(label) { mutableStateOf("") }
    var url by rememberSaveable(label) { mutableStateOf(baseUrl) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                if (hasKey) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Key set",
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            if (needsBaseUrl) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Base URL (e.g. http://192.168.1.10:1234/v1)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onSaveBaseUrl(url) }, enabled = url.isNotBlank()) { Text("Save URL") }
            }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(if (hasKey) "Replace API key" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onSaveKey(key); key = "" }, enabled = key.isNotBlank()) {
                Text("Save key")
            }
        }
    }
}
