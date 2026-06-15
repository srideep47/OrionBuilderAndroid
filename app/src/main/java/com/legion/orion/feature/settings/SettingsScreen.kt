package com.legion.orion.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.design.BadgeTone
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.CosmosBadge
import com.legion.orion.core.design.Liquid
import com.legion.orion.core.design.LiquidButton
import com.legion.orion.core.design.LiquidButtonStyle
import com.legion.orion.core.design.LiquidGroup
import com.legion.orion.core.design.LiquidRow
import com.legion.orion.core.design.LocalBottomChromeHeight
import com.legion.orion.core.design.MonoFamily
import com.legion.orion.core.design.liquidGlass

/** Settings — providers, agent workflow, context compaction, app behavior. */
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 10.dp,
            bottom = LocalBottomChromeHeight.current + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleLarge.copy(brush = Cosmos.TitleGradient),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Providers, agent workflow, and app behavior",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                )
            }
        }

        // ── Agent workflow ──
        item {
            LiquidGroup(title = "Agent workflow") {
                SliderRow(
                    title = "Max agent steps",
                    subtitle = "Tool-using turns before the agent stops",
                    value = ui.maxAgentIterations.toFloat(),
                    range = 4f..40f,
                    display = ui.maxAgentIterations.toString(),
                    onChange = { vm.setMaxAgentIterations(it.toInt()) },
                )
                LiquidRow(
                    title = "Haptic feedback",
                    subtitle = "Subtle ticks on taps and toggles",
                    showDivider = false,
                    trailing = {
                        LiquidSwitch(ui.hapticsEnabled, vm::setHapticsEnabled)
                    },
                )
            }
        }

        // ── Context compaction ──
        item {
            LiquidGroup(title = "Context compaction") {
                LiquidRow(
                    title = "Auto-compact long chats",
                    subtitle = "Summarize old turns near the context limit",
                    trailing = { LiquidSwitch(ui.compactionEnabled, vm::setCompactionEnabled) },
                )
                SliderRow(
                    title = "Trigger threshold",
                    subtitle = "Compact when context passes this fill level",
                    value = ui.compactionThresholdPercent.toFloat(),
                    range = 50f..95f,
                    display = "${ui.compactionThresholdPercent}%",
                    onChange = { vm.setCompactionThresholdPercent(it.toInt()) },
                )
                SliderRow(
                    title = "Keep recent turns",
                    subtitle = "Turns kept verbatim after compaction",
                    value = ui.compactionKeepRecentTurns.toFloat(),
                    range = 2f..20f,
                    display = ui.compactionKeepRecentTurns.toString(),
                    onChange = { vm.setCompactionKeepRecentTurns(it.toInt()) },
                )
                LiquidRow(
                    title = "Notify on compaction",
                    showDivider = false,
                    trailing = { LiquidSwitch(ui.compactionNotify, vm::setCompactionNotify) },
                )
            }
        }

        // ── Providers ──
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "CLOUD PROVIDERS",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                    modifier = Modifier.padding(start = 18.dp),
                )
                ui.rows.forEach { row ->
                    ProviderCard(row, onSaveKey = vm::saveKey, onSaveBaseUrl = vm::saveBaseUrl)
                }
            }
        }

        // ── About ──
        item {
            LiquidGroup(title = "About") {
                LiquidRow(title = "App", trailing = {
                    Text("Orion Builder", style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
                })
                LiquidRow(title = "Engine", trailing = {
                    Text("MNN · arm64-v8a", style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily), color = Cosmos.TextMuted)
                })
                LiquidRow(
                    title = "Secrets",
                    subtitle = "API keys are encrypted with the Android Keystore",
                    showDivider = false,
                    trailing = { CosmosBadge("Keystore", BadgeTone.Green) },
                )
            }
        }
    }
}

@Composable
private fun LiquidSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Cosmos.Violet2,
            checkedThumbColor = Color.White,
            uncheckedTrackColor = Cosmos.GlassFillStrong,
            uncheckedBorderColor = Cosmos.Hairline,
        ),
    )
}

@Composable
private fun SliderRow(
    title: String,
    subtitle: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = Cosmos.Text)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
                }
            }
            Text(
                display,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
                color = Cosmos.Violet3,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Cosmos.Violet,
                activeTrackColor = Cosmos.Violet2,
                inactiveTrackColor = Cosmos.GlassFillStrong,
            ),
        )
    }
}

@Composable
private fun ProviderCard(
    row: ProviderRow,
    onSaveKey: (com.legion.orion.core.database.ProviderEntity, String) -> Unit,
    onSaveBaseUrl: (com.legion.orion.core.database.ProviderEntity, String) -> Unit,
) {
    var key by rememberSaveable("key-${row.provider.id}") { mutableStateOf("") }
    var baseUrl by rememberSaveable("url-${row.provider.id}") { mutableStateOf(row.provider.baseUrl ?: "") }

    Column(
        Modifier
            .fillMaxWidth()
            .liquidGlass(shape = Liquid.ShapeMedium, fillTopAlpha = 0.08f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.provider.label,
                style = MaterialTheme.typography.titleSmall,
                color = Cosmos.Text,
                modifier = Modifier.weight(1f),
            )
            if (row.hasKey) {
                Icon(Icons.Outlined.CheckCircle, null, tint = Cosmos.Green, modifier = Modifier.size(15.dp))
                Spacer(Modifier.size(5.dp))
                Text("Key saved", style = MaterialTheme.typography.labelSmall, color = Cosmos.Green)
            } else {
                Text("No key", style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
            }
        }

        SettingsField(
            value = key,
            onChange = { key = it },
            hint = if (row.hasKey) "Replace API key…" else "API key…",
            password = true,
        )
        if (row.needsBaseUrl) {
            SettingsField(value = baseUrl, onChange = { baseUrl = it }, hint = "Base URL (OpenAI-compatible)…")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiquidButton(
                "Save",
                compact = true,
                enabled = key.isNotBlank() || (row.needsBaseUrl && baseUrl.isNotBlank()),
                onClick = {
                    if (key.isNotBlank()) {
                        onSaveKey(row.provider, key)
                        key = ""
                    }
                    if (row.needsBaseUrl && baseUrl.isNotBlank()) onSaveBaseUrl(row.provider, baseUrl)
                },
            )
            if (row.hasKey) {
                LiquidButton(
                    "Clear key",
                    compact = true,
                    style = LiquidButtonStyle.Glass,
                    onClick = { onSaveKey(row.provider, "") },
                )
            }
        }
    }
}

@Composable
private fun SettingsField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    password: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.06f)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Cosmos.Text, fontFamily = MonoFamily),
            cursorBrush = SolidColor(Cosmos.Violet),
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(hint, style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextFaint)
                    }
                    inner()
                }
            },
        )
    }
}
