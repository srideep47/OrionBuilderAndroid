package com.legion.orion.marta

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.Liquid
import com.legion.orion.core.design.LiquidButton
import com.legion.orion.core.design.LiquidButtonStyle
import com.legion.orion.core.design.LocalBottomChromeHeight
import com.legion.orion.core.design.WorkspaceHeader
import com.legion.orion.core.design.liquidGlass
import com.legion.orion.data.chat.MsgRole
import com.legion.orion.data.chat.PartType
import com.legion.orion.data.chat.UiMessage

@Composable
fun MartaScreen(vm: MartaViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val messages by vm.transcript.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val context = LocalContext.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startCall() else vm.hangUp()
    }

    LaunchedEffect(messages.size, ui.liveText, ui.partial) {
        val last = messages.size + if (ui.liveText.isNotBlank() || ui.partial.isNotBlank()) 1 else 0
        if (last > 0) runCatching { list.animateScrollToItem(last) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        WorkspaceHeader(
            title = "Marta",
            subtitle = ui.status,
            status = {
                if (ui.call) {
                    Text("ON CALL", style = MaterialTheme.typography.labelSmall, color = Cosmos.Green)
                }
            },
        )

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && ui.liveText.isBlank() && !ui.loading) {
                item { EmptyHint(ui) }
            }
            items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
            if (ui.partial.isNotBlank()) {
                item { Bubble(ui.partial, user = true, dim = true) }
            }
            if (ui.liveText.isNotBlank()) {
                item { Bubble(ui.liveText, user = false, dim = false) }
            }
            ui.error?.let { item { Text(it, color = Cosmos.Red, style = MaterialTheme.typography.bodySmall) } }
        }

        CallOrb(
            ui = ui,
            onCall = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (ui.call) vm.hangUp()
                else if (granted) vm.startCall()
                else askMic.launch(Manifest.permission.RECORD_AUDIO)
            },
            onStop = vm::stopTurn,
            onRetry = vm::retryLoad,
        )

        Composer(
            value = ui.input,
            enabled = ui.ready && !ui.busy,
            onChange = vm::onInput,
            onSend = vm::sendText,
        )
        Spacer(Modifier.height(LocalBottomChromeHeight.current))
    }
}

@Composable
private fun EmptyHint(ui: MartaUi) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 36.dp)
            .liquidGlass(shape = Liquid.ShapeMedium)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Talk to me like I’m on the line.", color = Cosmos.Text, fontWeight = FontWeight.SemiBold)
        Text(
            "Tap the orb for a call. Type if you’d rather text. I run Orion — models, projects, settings — and I hand hard work to Claude, Grok, or Codex.",
            color = Cosmos.TextMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!ui.ready) {
            Text(ui.status, color = Cosmos.Amber, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MessageBubble(msg: UiMessage) {
    val user = msg.role == MsgRole.USER
    val text = msg.parts.filter { it.type == PartType.TEXT }.joinToString("\n") { it.text }
    val tools = msg.parts.mapNotNull { it.tool }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        if (text.isNotBlank()) Bubble(text, user, dim = false)
        tools.forEach { tool ->
            Text(
                "· ${tool.name}  ${tool.status}",
                color = Cosmos.TextFaint,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Bubble(text: String, user: Boolean, dim: Boolean) {
    Box(
        Modifier
            .fillMaxWidth(0.92f)
            .liquidGlass(shape = Liquid.ShapeMedium, fillTopAlpha = if (user) 0.16f else 0.08f)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            color = if (dim) Cosmos.TextMuted else Cosmos.Text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CallOrb(ui: MartaUi, onCall: () -> Unit, onStop: () -> Unit, onRetry: () -> Unit) {
    val pulse by animateFloatAsState(
        targetValue = 1f + ui.level * 0.18f + if (ui.phase == MartaVoice.Phase.HEARING || ui.phase == MartaVoice.Phase.SPEAKING) 0.06f else 0f,
        label = "orb",
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(88.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(if (ui.call) Cosmos.Violet2 else Color(0x33221A44))
                .border(1.5.dp, if (ui.call) Cosmos.Violet else Cosmos.Hairline, CircleShape)
                .clickable(enabled = ui.ready || ui.error != null, onClick = onCall),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (ui.call) Icons.Outlined.CallEnd else Icons.Outlined.GraphicEq,
                contentDescription = if (ui.call) "Hang up" else "Start call",
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.busy) {
                LiquidButton("Stop", onStop, style = LiquidButtonStyle.Glass, compact = true, icon = Icons.Outlined.Stop)
            }
            if (!ui.ready) {
                LiquidButton("Retry load", onRetry, style = LiquidButtonStyle.Glass, compact = true, icon = Icons.Outlined.Refresh)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Composer(value: String, enabled: Boolean, onChange: (String) -> Unit, onSend: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .liquidGlass(shape = Liquid.ShapePill)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Cosmos.Text),
            cursorBrush = SolidColor(Cosmos.Violet),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) Text("Message Marta…", color = Cosmos.TextFaint, style = MaterialTheme.typography.bodyMedium)
                inner()
            },
        )
        Icon(
            Icons.AutoMirrored.Outlined.Send,
            contentDescription = "Send",
            tint = if (enabled && value.isNotBlank()) Cosmos.Violet3 else Cosmos.TextFaint,
            modifier = Modifier
                .size(22.dp)
                .clickable(enabled = enabled && value.isNotBlank(), onClick = onSend),
        )
    }
}
