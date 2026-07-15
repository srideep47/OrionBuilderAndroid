package com.legion.orion.feature.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.database.ChatEntity
import com.legion.orion.core.database.ProviderEntity
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.Liquid
import com.legion.orion.core.design.LiquidButton
import com.legion.orion.core.design.LiquidChip
import com.legion.orion.core.design.LiquidIconButton
import com.legion.orion.core.design.LiquidProgress
import com.legion.orion.core.design.LiquidSegmented
import com.legion.orion.core.design.LocalBottomChromeHeight
import com.legion.orion.core.design.MarkdownText
import com.legion.orion.core.design.MonoFamily
import com.legion.orion.core.design.liquidGlass
import com.legion.orion.core.design.rememberHaptic
import com.legion.orion.data.chat.MsgRole
import com.legion.orion.data.chat.MsgStatus
import com.legion.orion.data.chat.PartType
import com.legion.orion.data.chat.ToolView
import com.legion.orion.data.chat.UiMessage
import com.legion.orion.data.providers.ProviderType
import com.legion.orion.feature.editor.EditorScreen
import com.legion.orion.feature.preview.PreviewScreen
import com.legion.orion.inference.EngineState

// The conversation surface, header, model/history/options sheets, and message rendering below are
// the reusable building blocks of the unified Code space (see feature/code/CodeScreen.kt) — the old
// standalone ChatScreen() scaffold was removed when Chat merged into Code.

// ─────────────────────────────── Header ───────────────────────────────

@Composable
internal fun CommandHeader(
    ui: ChatUiState,
    engine: EngineState,
    onMenu: () -> Unit,
    onModelPicker: () -> Unit,
    onNewChat: () -> Unit,
    onProjectPicker: (() -> Unit)? = null,
) {
    val projectAware = ui.mode == ChatMode.BUILD || ui.mode == ChatMode.CLAUDE_CODE
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LiquidIconButton(Icons.Outlined.Menu, "History", onMenu)

        Column(
            Modifier
                .weight(1f)
                .then(
                    if (projectAware && onProjectPicker != null) Modifier.clickable(onClick = onProjectPicker)
                    else Modifier,
                ),
        ) {
            Text(
                "Orion",
                style = MaterialTheme.typography.titleLarge.copy(brush = Cosmos.TitleGradient),
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val sub = buildString {
                    append(
                        when (ui.mode) {
                            ChatMode.BUILD -> "Build agent"
                            ChatMode.CLAUDE_CODE -> "Claude Code"
                            ChatMode.CHAT -> "Assistant"
                        },
                    )
                    append(" · ")
                    append(ui.projectName ?: "no project")
                }
                Text(sub, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted, maxLines = 1)
                if (projectAware && onProjectPicker != null) {
                    Icon(Icons.Outlined.ExpandMore, "Switch project", tint = Cosmos.TextFaint, modifier = Modifier.size(12.dp))
                }
            }
        }

        ModelPill(ui = ui, engine = engine, onClick = onModelPicker)
        LiquidIconButton(Icons.Outlined.Add, "New chat", onNewChat)
    }
}

@Composable
private fun ModelPill(ui: ChatUiState, engine: EngineState, onClick: () -> Unit) {
    if (ui.mode == ChatMode.CLAUDE_CODE) {
        ClaudeCodeModelPill(ui.claudeCode, onClick)
        return
    }
    val provider = ui.selectedProvider
    val ready = engine.status.name == "READY" || engine.status.name == "GENERATING"
    Row(
        Modifier
            .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.09f)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    when {
                        provider?.type == ProviderType.MNN_LOCAL && ready -> Cosmos.Green
                        provider?.type == ProviderType.MNN_LOCAL -> Cosmos.Amber
                        provider != null -> Cosmos.Blue
                        else -> Cosmos.Red
                    },
                ),
        )
        Column {
            Text(
                provider?.label ?: "Pick model",
                style = MaterialTheme.typography.labelMedium,
                color = Cosmos.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp),
            )
            if (ui.selectedModel.isNotBlank()) {
                Text(
                    ui.selectedModel.substringAfterLast('/').take(24),
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 110.dp),
                )
            }
        }
        Icon(Icons.Outlined.ExpandMore, null, tint = Cosmos.TextMuted, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun ClaudeCodeModelPill(cc: ClaudeCodeUiState, onClick: () -> Unit) {
    Row(
        Modifier
            .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.09f)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (cc.loggedIn) Cosmos.Green else Cosmos.Amber),
        )
        Column {
            Text(
                cc.model ?: "Default",
                style = MaterialTheme.typography.labelMedium,
                color = Cosmos.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp),
            )
            Text(
                "${cc.effort} effort",
                style = MaterialTheme.typography.labelSmall,
                color = Cosmos.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp),
            )
        }
        Icon(Icons.Outlined.ExpandMore, null, tint = Cosmos.TextMuted, modifier = Modifier.size(15.dp))
    }
}

// ─────────────────────────────── Chat body ───────────────────────────────

/** The conversation surface: transcript, stats, Claude Code setup banner, suggestions, and the
 * input bar with its mode toggle (CHAT / BUILD / CLAUDE_CODE). Reused by the unified Code space
 * (see feature/code/CodeScreen.kt) in both portrait and landscape; carries no header or panel tabs. */
@Composable
internal fun ChatContent(
    ui: ChatUiState,
    messages: List<UiMessage>,
    vm: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val bottomChrome = LocalBottomChromeHeight.current
    // The floating nav pill is covered by the keyboard while it's up, so the extra clearance
    // reserved for it becomes dead space between the input bar and the keyboard — only reserve it
    // when the IME is actually hidden. WindowInsets.ime updates live as the keyboard animates in/out.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // Keys on parts.size + each tool's status (not just text length) — otherwise adding/updating a
    // tool-call card (Claude Code executing Read/Write/Bash, etc.) never triggers a re-scroll, since a
    // ToolView part's own `text` is empty. That silently hid the entire tool-call process from view
    // whenever a turn was mostly tool calls with little running text alongside them.
    val lastMessageShape = messages.lastOrNull()?.parts?.let { parts ->
        parts.size to parts.sumOf { it.text.length + (it.tool?.status?.length ?: 0) }
    }
    LaunchedEffect(messages.size, lastMessageShape) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                EmptyChat(mode = ui.mode)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageView(msg = msg, onUndo = vm::rollback, onRetry = vm::retryLast)
                    }
                }
            }

            ui.error?.let { NoticeBanner(it, isError = true, Modifier.align(Alignment.BottomCenter)) }
            ui.notice?.let { NoticeBanner(it, isError = false, Modifier.align(Alignment.BottomCenter)) }
        }

        AnimatedVisibility(
            visible = ui.busy || ui.liveTokens > 0 || (ui.mode == ChatMode.CLAUDE_CODE && ui.claudeCode.sessionCostUsd > 0),
        ) {
            if (ui.mode == ChatMode.CLAUDE_CODE) ClaudeCodeStatsStrip(ui) else StatsStrip(ui)
        }

        if (ui.mode == ChatMode.CLAUDE_CODE && (!ui.claudeCode.installed || !ui.claudeCode.loggedIn || ui.claudeCode.installProgress != null)) {
            ClaudeCodeSetupBanner(
                ui = ui,
                onInstall = vm::installClaudeCodeRuntime,
                onSignIn = vm::beginClaudeCodeLogin,
                onSubmitCode = vm::submitClaudeCodeLoginCode,
            )
        }

        if (messages.isEmpty()) {
            SuggestionChips(mode = ui.mode, onPick = { vm.onInput(it) })
        }

        InputBar(
            ui = ui,
            onInput = vm::onInput,
            onSend = vm::send,
            onStop = vm::stop,
            onToggleMode = {
                vm.setMode(
                    when (ui.mode) {
                        ChatMode.CHAT -> ChatMode.BUILD
                        ChatMode.BUILD -> ChatMode.CLAUDE_CODE
                        ChatMode.CLAUDE_CODE -> ChatMode.CHAT
                    },
                )
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = if (imeVisible) 4.dp else bottomChrome + 4.dp)
                .imePadding(),
        )
    }
}

@Composable
private fun StatsStrip(ui: ChatUiState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui.busy) {
            CircularProgressIndicator(
                Modifier.size(11.dp),
                color = Cosmos.Violet,
                strokeWidth = 1.5.dp,
            )
        }
        Text(
            "${ui.liveTokens} tok · ${"%.1f".format(ui.liveTokensPerSecond)} tok/s",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = Cosmos.TextMuted,
        )
        Spacer(Modifier.weight(1f))
        val used = ui.contextUsedTokens
        val window = ui.contextWindowTokens.coerceAtLeast(1)
        Text(
            "ctx ${used / 1000}k/${window / 1000}k",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = if (used.toFloat() / window > 0.75f) Cosmos.Amber else Cosmos.TextMuted,
        )
        Box(Modifier.width(64.dp)) {
            LiquidProgress(progress = used.toFloat() / window, height = 3.dp)
        }
    }
}

/** Claude Code's own real usage/cost numbers (from the CLI's `result` event — see
 * [com.legion.orion.claudecode.StreamJsonEventMapper.TurnUsage]), not an estimate: cumulative session
 * cost on the left, last turn's input/output/cache token counts on the right. */
@Composable
private fun ClaudeCodeStatsStrip(ui: ChatUiState) {
    val cc = ui.claudeCode
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui.busy) {
            CircularProgressIndicator(
                Modifier.size(11.dp),
                color = Cosmos.Violet,
                strokeWidth = 1.5.dp,
            )
        }
        Text(
            "$" + "%.4f".format(cc.sessionCostUsd),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = Cosmos.TextMuted,
        )
        Spacer(Modifier.weight(1f))
        val cacheSuffix = if (cc.lastTurnCacheReadTokens > 0) " · cache ${compactTokenCount(cc.lastTurnCacheReadTokens)}" else ""
        Text(
            "in ${compactTokenCount(cc.lastTurnInputTokens)} · out ${compactTokenCount(cc.lastTurnOutputTokens)}$cacheSuffix",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = Cosmos.TextMuted,
        )
    }
}

private fun compactTokenCount(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

@Composable
private fun NoticeBanner(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(Liquid.ShapeSmall)
            .background(if (isError) Color(0xE63A1218) else Color(0xE6151030))
            .border(
                1.dp,
                if (isError) Cosmos.Red.copy(alpha = 0.4f) else Cosmos.Violet.copy(alpha = 0.4f),
                Liquid.ShapeSmall,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (isError) Cosmos.Red else Cosmos.Violet3,
        )
    }
}

/**
 * Guides one-time setup for the Claude Code mode: bundled-runtime install, then subscription
 * sign-in. Only rendered while something needs the user's attention (see call site); once installed
 * and signed in, disappears and the mode behaves like any other.
 */
@Composable
private fun ClaudeCodeSetupBanner(
    ui: ChatUiState,
    onInstall: () -> Unit,
    onSignIn: () -> Unit,
    onSubmitCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cc = ui.claudeCode
    val context = LocalContext.current
    var pastedCode by remember { mutableStateOf("") }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.1f)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Set up Claude Code", style = MaterialTheme.typography.labelLarge, color = Cosmos.Text)
        when {
            cc.installProgress != null -> {
                Text(cc.installProgress, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
            }
            !cc.installed -> {
                Text(
                    "Runs the real Claude Code CLI on-device, billed to your Claude subscription — " +
                        "not pay-per-token API. One-time install, about 150MB.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                )
                LiquidButton("Install", onClick = onInstall, compact = true)
            }
            cc.loginUrl != null -> {
                Text(
                    "Open this link, sign in with your Claude account, then paste back whatever code the page shows.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                )
                LiquidButton(
                    "Open sign-in page",
                    compact = true,
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cc.loginUrl))) } },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicTextField(
                        value = pastedCode,
                        onValueChange = { pastedCode = it },
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.08f)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = Cosmos.Text),
                        cursorBrush = SolidColor(Cosmos.Violet),
                        decorationBox = { inner ->
                            if (pastedCode.isEmpty()) {
                                Text("Paste code…", style = MaterialTheme.typography.bodySmall, color = Cosmos.TextFaint)
                            }
                            inner()
                        },
                    )
                    LiquidButton("Submit", compact = true, onClick = { onSubmitCode(pastedCode); pastedCode = "" })
                }
            }
            !cc.loggedIn -> {
                Text(
                    "Sign in with your Claude Pro, Max, or Team subscription — no separate API billing.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                )
                LiquidButton(if (cc.loginBusy) "Starting…" else "Sign in", compact = true, onClick = onSignIn)
            }
        }
        cc.loginError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Cosmos.Red) }
    }
}

@Composable
private fun EmptyChat(mode: ChatMode) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val pulse = rememberInfiniteTransition(label = "pulse")
        val glow by pulse.animateFloat(
            0.5f, 1f,
            infiniteRepeatable(tween(2400), RepeatMode.Reverse),
            label = "glow",
        )
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Cosmos.Violet.copy(alpha = 0.5f * glow), Color.Transparent),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                null,
                tint = Cosmos.Violet3,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            when (mode) {
                ChatMode.BUILD -> "What should we build?"
                ChatMode.CLAUDE_CODE -> "What should Claude Code do?"
                ChatMode.CHAT -> "Ask Orion anything"
            },
            style = MaterialTheme.typography.headlineSmall.copy(brush = Cosmos.TitleGradient),
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when (mode) {
                ChatMode.BUILD -> "On-device agent with tools, snapshots and preview"
                ChatMode.CLAUDE_CODE -> "The real Claude Code CLI, billed to your subscription"
                ChatMode.CHAT -> "Local-first. Your words never have to leave the phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Cosmos.TextMuted,
        )
    }
}

@Composable
private fun SuggestionChips(mode: ChatMode, onPick: (String) -> Unit) {
    val suggestions = remember(mode) {
        if (mode == ChatMode.BUILD) {
            listOf("Create a landing page", "Build a todo app", "Make a portfolio site", "Start an Android app")
        } else {
            listOf("Explain transformers", "Write a haiku", "Plan my week", "Summarize an idea")
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        suggestions.forEach { s -> LiquidChip(s, onClick = { onPick(s) }) }
    }
}

// ─────────────────────────────── Input bar ───────────────────────────────

@Composable
private fun InputBar(
    ui: ChatUiState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberHaptic()
    Row(
        modifier
            .fillMaxWidth()
            .liquidGlass(
                shape = RoundedCornerShape(26.dp),
                fillTopAlpha = 0.12f,
                fillBottomAlpha = 0.05f,
                floating = true,
            )
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val accentMode = ui.mode != ChatMode.CHAT
        Row(
            Modifier
                .clip(Liquid.ShapePill)
                .background(
                    if (accentMode) Brush.verticalGradient(
                        listOf(Cosmos.Violet.copy(alpha = 0.35f), Cosmos.Violet2.copy(alpha = 0.25f)),
                    ) else SolidColor(Color.Transparent),
                    Liquid.ShapePill,
                )
                .border(
                    1.dp,
                    if (accentMode) Cosmos.Violet.copy(alpha = 0.55f) else Cosmos.Hairline,
                    Liquid.ShapePill,
                )
                .clickable { haptic(); onToggleMode() }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                when (ui.mode) {
                    ChatMode.BUILD -> Icons.Outlined.Build
                    ChatMode.CLAUDE_CODE -> Icons.Outlined.Terminal
                    ChatMode.CHAT -> Icons.Outlined.ChatBubbleOutline
                },
                contentDescription = "Mode",
                tint = if (accentMode) Cosmos.Violet3 else Cosmos.TextMuted,
                modifier = Modifier.size(15.dp),
            )
            Text(
                when (ui.mode) {
                    ChatMode.BUILD -> "Build"
                    ChatMode.CLAUDE_CODE -> "Claude Code"
                    ChatMode.CHAT -> "Chat"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (accentMode) Cosmos.Violet3 else Cosmos.TextMuted,
            )
        }

        BasicTextField(
            value = ui.input,
            onValueChange = onInput,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Cosmos.Text),
            cursorBrush = SolidColor(Cosmos.Violet),
            maxLines = 6,
            decorationBox = { inner ->
                Box {
                    if (ui.input.isEmpty()) {
                        Text(
                            if (ui.mode == ChatMode.BUILD) "Describe what to build…" else "Message Orion…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cosmos.TextFaint,
                        )
                    }
                    inner()
                }
            },
        )

        val canSend = ui.input.isNotBlank() && !ui.busy
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    when {
                        ui.busy -> Brush.verticalGradient(listOf(Color(0xFFFF6B76), Color(0xFFD8404C)))
                        canSend -> Brush.verticalGradient(listOf(Cosmos.Violet, Cosmos.Violet2))
                        else -> Liquid.fill(0.08f, 0.04f)
                    },
                    CircleShape,
                )
                .border(1.dp, Liquid.specular(1.2f), CircleShape)
                .clickable(enabled = canSend || ui.busy) {
                    haptic()
                    if (ui.busy) onStop() else onSend()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (ui.busy) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send,
                contentDescription = if (ui.busy) "Stop" else "Send",
                tint = if (canSend || ui.busy) Color.White else Cosmos.TextFaint,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

// ─────────────────────────────── Messages ───────────────────────────────

@Composable
private fun MessageView(msg: UiMessage, onUndo: (ToolView) -> Unit, onRetry: () -> Unit) {
    if (msg.role == MsgRole.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Cosmos.Violet.copy(alpha = 0.30f), Cosmos.Violet2.copy(alpha = 0.22f)),
                        ),
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(Cosmos.Violet.copy(alpha = 0.45f), Cosmos.Violet.copy(alpha = 0.15f)),
                        ),
                        RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    msg.parts.joinToString("\n") { it.text },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cosmos.Text,
                )
            }
        }
        return
    }

    // Assistant: parts rendered flat on the cosmos (modern AI-app style).
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (msg.parts.isEmpty() && msg.status == MsgStatus.STREAMING) {
            ThinkingShimmer()
        }
        msg.parts.forEach { part ->
            when (part.type) {
                PartType.THINKING -> ThinkingBlock(
                    text = part.text,
                    streaming = msg.status == MsgStatus.STREAMING,
                )
                PartType.TOOL_CALL -> part.tool?.let { ToolCard(it, onUndo) }
                else -> MarkdownText(part.text)
            }
        }
        if (msg.status != MsgStatus.STREAMING) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallAction(Icons.Outlined.ContentCopy, "Copy") {
                    clipboard.setText(
                        AnnotatedString(
                            msg.parts.filter { it.type == PartType.TEXT }.joinToString("\n") { it.text },
                        ),
                    )
                }
                SmallAction(Icons.Outlined.Refresh, "Retry", onRetry)
                if (msg.status == MsgStatus.ERROR) {
                    Text(
                        "failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = Cosmos.Red,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    Row(
        Modifier
            .clip(Liquid.ShapePill)
            .clickable { haptic(); onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = Cosmos.TextFaint, modifier = Modifier.size(13.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextFaint)
    }
}

@Composable
private fun ThinkingShimmer() {
    val t = rememberInfiniteTransition(label = "shimmer")
    val alpha by t.animateFloat(
        0.35f, 0.9f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "a",
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(
            Icons.Outlined.AutoAwesome,
            null,
            tint = Cosmos.Violet.copy(alpha = alpha),
            modifier = Modifier.size(15.dp),
        )
        Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted.copy(alpha = alpha))
    }
}

@Composable
private fun ThinkingBlock(text: String, streaming: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val haptic = rememberHaptic()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Liquid.ShapeSmall)
            .background(Color(0x21A88CFF))
            .border(1.dp, Cosmos.Violet.copy(alpha = 0.18f), Liquid.ShapeSmall),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { haptic(); expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Cosmos.Violet3, modifier = Modifier.size(14.dp))
            Text(
                if (streaming) "Thinking…" else "Reasoning",
                style = MaterialTheme.typography.labelMedium,
                color = Cosmos.Violet3,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                null,
                tint = Cosmos.TextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded || streaming,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                color = Cosmos.TextMuted,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                maxLines = if (streaming) 8 else Int.MAX_VALUE,
            )
        }
    }
}

@Composable
private fun ToolCard(tool: ToolView, onUndo: (ToolView) -> Unit) {
    var expanded by rememberSaveable(tool.name + tool.args.hashCode()) { mutableStateOf(false) }
    val haptic = rememberHaptic()
    val (dotColor, statusLabel) = when (tool.status) {
        "running" -> Cosmos.Blue to "running"
        "done" -> Cosmos.Green to "done"
        else -> Cosmos.Red to "error"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.07f),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { haptic(); expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tool.status == "running") {
                CircularProgressIndicator(Modifier.size(12.dp), color = Cosmos.Blue, strokeWidth = 1.5.dp)
            } else {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            }
            Text(
                tool.name,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
                color = Cosmos.Text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = dotColor)
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                null,
                tint = Cosmos.TextMuted,
                modifier = Modifier.size(15.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (tool.args.isNotBlank()) {
                    Text(
                        tool.args,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                        color = Cosmos.TextMuted,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val diff = tool.diff
                if (diff != null) {
                    DiffView(diff)
                } else {
                    tool.result?.takeIf { it.isNotBlank() }?.let { result ->
                        Text(
                            result,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                            color = Cosmos.TextDim,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x66000000))
                                .padding(9.dp),
                            maxLines = 24,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (tool.snapshotRef != null) {
                    Row(
                        Modifier
                            .clip(Liquid.ShapePill)
                            .clickable { haptic(); onUndo(tool) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(Icons.Outlined.Refresh, null, tint = Cosmos.Amber, modifier = Modifier.size(13.dp))
                        Text("Undo this change", style = MaterialTheme.typography.labelSmall, color = Cosmos.Amber)
                    }
                }
            }
        }
    }
}

/** Renders a unified diff body (as produced by [com.legion.orion.claudecode.StreamJsonEventMapper])
 * with +/- line coloring, matching the "diff viewer" panel real Claude Code shows for edits. */
@Composable
private fun DiffView(diff: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x66000000))
            .padding(vertical = 6.dp),
    ) {
        diff.lineSequence().take(200).forEach { line ->
            val (color, bg) = when {
                line.startsWith("@@") -> Cosmos.Violet3 to Color.Transparent
                line.startsWith("+") -> Cosmos.Green to Color(0x1F2ECC71)
                line.startsWith("-") -> Cosmos.Red to Color(0x1FFF5252)
                else -> Cosmos.TextDim to Color.Transparent
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 9.dp, vertical = 1.dp),
            )
        }
    }
}

// ─────────────────────────────── Sheets ───────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistorySheet(
    chats: List<ChatEntity>,
    activeId: String?,
    onOpen: (ChatEntity) -> Unit,
    onDelete: (ChatEntity) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(chats, query) {
        if (query.isBlank()) chats
        else chats.filter { it.title.contains(query, ignoreCase = true) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF20A0618),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Chats",
                    style = MaterialTheme.typography.titleMedium.copy(brush = Cosmos.TitleGradient),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                LiquidButton("New chat", onClick = onNew, compact = true, icon = Icons.Outlined.Add)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.08f)
                    .padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Search, null, tint = Cosmos.TextMuted, modifier = Modifier.size(16.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Cosmos.Text),
                    cursorBrush = SolidColor(Cosmos.Violet),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text("Search chats…", style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextFaint)
                            }
                            inner()
                        }
                    },
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                items(filtered, key = { it.id }) { chat ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(Liquid.ShapeSmall)
                            .background(if (chat.id == activeId) Color(0x1FA88CFF) else Color.Transparent)
                            .clickable { onOpen(chat) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            when (chat.mode) {
                                "build", "agent" -> Icons.Outlined.Build
                                "claude_code" -> Icons.Outlined.Terminal
                                else -> Icons.Outlined.ChatBubbleOutline
                            },
                            null,
                            tint = if (chat.id == activeId) Cosmos.Violet3 else Cosmos.TextMuted,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            chat.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (chat.id == activeId) Cosmos.Text else Cosmos.TextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Outlined.Delete,
                            "Delete",
                            tint = Cosmos.TextFaint,
                            modifier = Modifier
                                .size(17.dp)
                                .clickable { onDelete(chat) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Model/effort/permission-mode picker for Claude Code mode — mirrors [ModelSheet]'s look, but these
 * three values map directly to `claude` CLI flags (see `ClaudeCodeProcess`) rather than an Orion
 * `ProviderEntity`, so it's a separate, simpler sheet. Changing any value restarts the current
 * project's live Claude Code process (with --resume) via `ChatViewModel.setClaudeCodeOptions`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClaudeCodeOptionsSheet(
    ui: ChatUiState,
    onPick: (model: String?, effort: String, permissionMode: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cc = ui.claudeCode

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF20A0618),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Claude Code options",
                style = MaterialTheme.typography.titleMedium.copy(brush = Cosmos.TitleGradient),
                fontWeight = FontWeight.Bold,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Model", style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ClaudeCodeUiState.MODEL_OPTIONS.forEach { model ->
                        LiquidChip(
                            text = model ?: "Default",
                            selected = cc.model == model,
                            onClick = { onPick(model, cc.effort, cc.permissionMode) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Effort", style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ClaudeCodeUiState.EFFORT_OPTIONS.forEach { effort ->
                        LiquidChip(
                            text = effort,
                            selected = cc.effort == effort,
                            onClick = { onPick(cc.model, effort, cc.permissionMode) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Permission mode", style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ClaudeCodeUiState.PERMISSION_MODE_OPTIONS.forEach { mode ->
                        LiquidChip(
                            text = mode,
                            selected = cc.permissionMode == mode,
                            onClick = { onPick(cc.model, cc.effort, mode) },
                        )
                    }
                }
                Text(
                    "bypassPermissions is recommended — it's the only mode that runs Bash commands " +
                        "(builds, dev servers, etc.) unattended. Other modes may block or hang waiting " +
                        "for an approval no one is here to give.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextFaint,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheet(
    ui: ChatUiState,
    onPick: (ProviderEntity, String) -> Unit,
    onProviderFocus: (ProviderEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var focused by remember { mutableStateOf(ui.selectedProvider) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF20A0618),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Model",
                style = MaterialTheme.typography.titleMedium.copy(brush = Cosmos.TitleGradient),
                fontWeight = FontWeight.Bold,
            )

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ui.providers.forEach { p ->
                    val icon = when (p.type) {
                        ProviderType.MNN_LOCAL -> Icons.Outlined.Bolt
                        ProviderType.PEER -> Icons.Outlined.AutoAwesome
                        else -> null
                    }
                    LiquidChip(
                        text = p.label,
                        selected = focused?.id == p.id,
                        icon = icon,
                        onClick = {
                            focused = p
                            onProviderFocus(p)
                        },
                    )
                }
            }

            val provider = focused
            if (provider != null) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                ) {
                    items(ui.modelSheetModels) { model ->
                        val active = ui.selectedProvider?.id == provider.id && ui.selectedModel == model
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(Liquid.ShapeSmall)
                                .background(if (active) Color(0x1FA88CFF) else Color.Transparent)
                                .clickable { onPick(provider, model) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                model,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                                color = if (active) Cosmos.Text else Cosmos.TextDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (active) {
                                Icon(Icons.Outlined.Check, null, tint = Cosmos.Green, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    if (ui.modelSheetModels.isEmpty()) {
                        items(listOf("none")) {
                            Text(
                                "No models for this provider yet — download one in Models.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Cosmos.TextMuted,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
