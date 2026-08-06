package com.legion.orion.feature.chat

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
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
import com.legion.orion.core.design.LiquidButtonStyle
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
import com.legion.orion.data.chat.ChatAttachment
import com.legion.orion.data.chat.MsgStatus
import com.legion.orion.data.chat.PartType
import com.legion.orion.data.chat.ToolView
import com.legion.orion.data.chat.UiMessage
import com.legion.orion.data.providers.ProviderType
import com.legion.orion.feature.editor.EditorScreen
import com.legion.orion.feature.preview.PreviewScreen
import com.legion.orion.inference.EngineState
import coil.compose.AsyncImage
import java.io.File

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
    // No app-name/mode-name title text here — deliberately removed (owner direction: it doesn't fit
    // alongside the project + model pills on a ~430dp usable-width phone without wrapping to 2 lines).
    // Don't re-add title/subtitle text to this header without checking first.
    val projectAware = ui.mode == ChatMode.BUILD || ui.mode.isAgentCli
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LiquidIconButton(Icons.Outlined.Menu, "History", onMenu)
        if (projectAware && onProjectPicker != null) {
            ProjectPill(ui = ui, onClick = onProjectPicker)
        }
        ModelPill(ui = ui, engine = engine, onClick = onModelPicker)
        Spacer(Modifier.weight(1f))
        LiquidIconButton(Icons.Outlined.Add, "New chat", onNewChat)
    }
}

/** Visible project-picker pill (previously only reachable by tapping hidden header subtitle text). */
@Composable
private fun ProjectPill(ui: ChatUiState, onClick: () -> Unit) {
    Row(
        Modifier
            .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.09f)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Cosmos.TextMuted, modifier = Modifier.size(14.dp))
        Text(
            ui.projectName ?: "No project",
            style = MaterialTheme.typography.labelMedium,
            color = Cosmos.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 104.dp),
        )
        Icon(Icons.Outlined.ExpandMore, null, tint = Cosmos.TextMuted, modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun ModelPill(ui: ChatUiState, engine: EngineState, onClick: () -> Unit) {
    if (ui.mode == ChatMode.CLAUDE_CODE) {
        ClaudeCodeModelPill(ui.claudeCode, onClick)
        return
    }
    if (ui.mode == ChatMode.CODEX) {
        CodexModelPill(ui.codex, onClick)
        return
    }
    val provider = ui.selectedProvider
    val ready = engine.status.name == "READY" || engine.status.name == "GENERATING"
    Row(
        Modifier
            .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.09f)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
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
        Text(
            ui.selectedModel.substringAfterLast('/').take(18).ifBlank { provider?.label ?: "Pick model" },
            style = MaterialTheme.typography.labelMedium,
            color = Cosmos.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        Icon(Icons.Outlined.ExpandMore, null, tint = Cosmos.TextMuted, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun CodexModelPill(cx: CodexUiState, onClick: () -> Unit) {
    Row(
        Modifier
            .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.09f)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (cx.loggedIn) Cosmos.Green else Cosmos.Amber),
        )
        Text(
            "${cx.model ?: "Default"} · ${claudeEffortLabel(cx.effort)}",
            style = MaterialTheme.typography.labelMedium,
            color = Cosmos.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 108.dp),
        )
        Icon(Icons.Outlined.ExpandMore, null, tint = Cosmos.TextMuted, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun ClaudeCodeModelPill(cc: ClaudeCodeUiState, onClick: () -> Unit) {
    Row(
        Modifier
            .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.09f)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (cc.loggedIn) Cosmos.Green else Cosmos.Amber),
        )
        Text(
            "${cc.model ?: "Default"} · ${claudeEffortLabel(cc.effort)}",
            style = MaterialTheme.typography.labelMedium,
            color = Cosmos.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 108.dp),
        )
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
    bottomContent: (@Composable (imeVisible: Boolean) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    var claudeUsageExpanded by rememberSaveable { mutableStateOf(true) }
    var followLatest by remember { mutableStateOf(true) }
    val bottomChrome = LocalBottomChromeHeight.current
    var commandPickerOpen by rememberSaveable { mutableStateOf(false) }
    // The floating nav pill is covered by the keyboard while it's up, so the extra clearance
    // reserved for it becomes dead space between the input bar and the keyboard — only reserve it
    // when the IME is actually hidden. WindowInsets.ime updates live as the keyboard animates in/out.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addClaudeCodeAttachments(uris)
    }

    // Keys on parts.size + each tool's status (not just text length) — otherwise adding/updating a
    // tool-call card (Claude Code executing Read/Write/Bash, etc.) never triggers a re-scroll, since a
    // ToolView part's own `text` is empty. That silently hid the entire tool-call process from view
    // whenever a turn was mostly tool calls with little running text alongside them.
    val lastMessageShape = messages.lastOrNull()?.parts?.let { parts ->
        parts.size to parts.sumOf { it.text.length + (it.tool?.status?.length ?: 0) }
    }
    // Follow streaming output only while the user is at the true bottom. The old implementation
    // aligned the last message's TOP on every delta/status change; for a reply taller than the
    // viewport that looked like the chat jumped back upward whenever the user reached its bottom.
    val listScrolling = listState.isScrollInProgress
    LaunchedEffect(listScrolling) {
        if (!listScrolling) {
            val layout = listState.layoutInfo
            followLatest = layout.totalItemsCount == 0 ||
                layout.visibleItemsInfo.lastOrNull()?.index == layout.totalItemsCount - 1
        }
    }
    LaunchedEffect(messages.size, lastMessageShape) {
        if (messages.isNotEmpty() && followLatest && !listState.isScrollInProgress) {
            // This is the dedicated anchor after the final message, so even a viewport-tall reply
            // scrolls to its end instead of placing its beginning at the top.
            listState.animateScrollToItem(messages.size)
        }
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
                    item(key = "chat-bottom-anchor") { Spacer(Modifier.height(1.dp)) }
                }
            }

            ui.error?.let { NoticeBanner(it, isError = true, Modifier.align(Alignment.BottomCenter)) }
            ui.notice?.let { NoticeBanner(it, isError = false, Modifier.align(Alignment.BottomCenter)) }
        }

        AnimatedVisibility(
            visible = ui.busy || ui.liveTokens > 0 ||
                (ui.mode == ChatMode.CLAUDE_CODE &&
                    (ui.claudeCode.loggedIn || ui.claudeCode.sessionCostUsd > 0 ||
                        ui.claudeCode.contextWindowTokens > 0)) ||
                (ui.mode == ChatMode.CODEX &&
                    (ui.codex.loggedIn || ui.codex.lastTurnOutputTokens > 0)),
        ) {
            when (ui.mode) {
                ChatMode.CLAUDE_CODE -> ClaudeCodeStatsStrip(ui, onClick = { claudeUsageExpanded = !claudeUsageExpanded })
                ChatMode.CODEX -> CodexStatsStrip(ui)
                // The generic strip's local token estimate is meaningless for either real CLI, which
                // report their own authoritative numbers.
                else -> StatsStrip(ui)
            }
        }
        AnimatedVisibility(
            visible = ui.mode == ChatMode.CLAUDE_CODE && ui.claudeCode.loggedIn && claudeUsageExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ClaudeCodeUsageDetails(ui, onRefresh = vm::refreshClaudeAccountUsage)
        }

        if (ui.mode == ChatMode.CLAUDE_CODE && (!ui.claudeCode.installed || !ui.claudeCode.loggedIn || ui.claudeCode.installProgress != null)) {
            ClaudeCodeSetupBanner(
                ui = ui,
                onInstall = vm::installClaudeCodeRuntime,
                onSignIn = vm::beginClaudeCodeLogin,
                onSubmitCode = vm::submitClaudeCodeLoginCode,
                onCancelSignIn = vm::cancelClaudeCodeLogin,
            )
        }

        if (ui.mode == ChatMode.CODEX && (!ui.codex.installed || !ui.codex.loggedIn || ui.codex.installProgress != null)) {
            CodexSetupBanner(
                ui = ui,
                onInstall = { vm.installCodexRuntime() },
                onSignIn = vm::beginCodexLogin,
                onCancelSignIn = vm::cancelCodexLogin,
            )
        }

        if (messages.isEmpty()) {
            SuggestionChips(mode = ui.mode, onPick = { vm.onInput(it) })
        }

        // A live development surface belongs to the same vertical layout as the transcript. Keeping
        // this slot above the composer makes the message list reserve its height instead of letting
        // an absolutely-positioned preview cover whichever messages happen to be at the bottom.
        bottomContent?.invoke(imeVisible)

        InputBar(
            ui = ui,
            onInput = vm::onInput,
            onAttach = { attachmentPicker.launch(arrayOf("*/*")) },
            onRemoveAttachment = vm::removeClaudeCodeAttachment,
            onSend = vm::send,
            onStop = vm::stop,
            onToggleMode = { commandPickerOpen = true },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = if (imeVisible) 4.dp else bottomChrome + 4.dp)
                .imePadding(),
        )

        if (commandPickerOpen) {
            CommandPickerSheet(
                ui = ui,
                onChoose = { target ->
                    // Engine-to-engine changes preserve the project and prepare a visible handoff;
                    // ordinary Chat/Build choices are immediate and never alter the selected model.
                    if (target.isAgentCli && ui.mode.isAgentCli && target != ui.mode) vm.switchEngine(target)
                    else vm.setMode(target)
                    commandPickerOpen = false
                },
                onDismiss = { commandPickerOpen = false },
            )
        }
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
private fun ClaudeCodeStatsStrip(ui: ChatUiState, onClick: () -> Unit) {
    val cc = ui.claudeCode
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
            if (cc.contextWindowTokens > 0) {
                "context ${"%.0f".format(cc.contextUsedPercentage ?: 0.0)}%"
            } else {
                "$" + "%.4f".format(cc.sessionCostUsd)
            },
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = Cosmos.TextMuted,
        )
        Spacer(Modifier.weight(1f))
        val cacheSuffix = if (cc.lastTurnCacheReadTokens > 0) " · cache ${compactTokenCount(cc.lastTurnCacheReadTokens)}" else ""
        Text(
            if (cc.contextWindowTokens > 0) {
                "${compactTokenCount(cc.contextUsedTokens)} / ${compactTokenCount(cc.contextWindowTokens)}"
            } else {
                "in ${compactTokenCount(cc.lastTurnInputTokens)} · out ${compactTokenCount(cc.lastTurnOutputTokens)}$cacheSuffix"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = Cosmos.TextMuted,
        )
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = "Show Claude usage",
            tint = Cosmos.TextFaint,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Codex's token strip. Shows tokens rather than a dollar figure because Codex reports no cost — see
 * [com.legion.orion.codex.CodexEventMapper.TurnUsage]. Not clickable: there is no equivalent of Claude's
 * expandable 5h/7d quota detail to open.
 */
@Composable
private fun CodexStatsStrip(ui: ChatUiState) {
    val cx = ui.codex
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
            "codex",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = Cosmos.TextMuted,
        )
        Spacer(Modifier.weight(1f))
        val cacheSuffix = if (cx.lastTurnCacheReadTokens > 0) {
            " · cached ${compactTokenCount(cx.lastTurnCacheReadTokens)}"
        } else {
            ""
        }
        Text(
            "in ${compactTokenCount(cx.lastTurnInputTokens)} · out ${compactTokenCount(cx.lastTurnOutputTokens)}$cacheSuffix",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = Cosmos.TextMuted,
        )
    }
}

@Composable
private fun ClaudeCodeUsageDetails(ui: ChatUiState, onRefresh: () -> Unit) {
    val cc = ui.claudeCode
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.08f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Claude usage limits",
                style = MaterialTheme.typography.labelMedium,
                color = Cosmos.TextDim,
            )
            Spacer(Modifier.weight(1f))
            if (cc.accountUsageRefreshing) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Cosmos.Violet)
            } else {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "Refresh Claude usage limits",
                    tint = Cosmos.TextMuted,
                    modifier = Modifier.size(17.dp).clickable(onClick = onRefresh),
                )
            }
        }
        UsageProgressRow(
            label = "Context window",
            detail = if (cc.contextWindowTokens > 0) {
                "${compactTokenCount(cc.contextUsedTokens)} / ${compactTokenCount(cc.contextWindowTokens)} " +
                    "(${"%.0f".format(cc.contextUsedPercentage ?: 0.0)}%) · " +
                    "${"%.0f".format(cc.contextRemainingPercentage ?: 0.0)}% remaining"
            } else {
                "Waiting for the first Claude response"
            },
            progress = ((cc.contextUsedPercentage ?: 0.0) / 100.0).toFloat(),
        )
        UsageProgressRow(
            label = "5-hour limit",
            detail = limitDetail(cc.fiveHourUsedPercentage, cc.fiveHourResetsAt),
            progress = ((cc.fiveHourUsedPercentage ?: 0.0) / 100.0).toFloat(),
        )
        UsageProgressRow(
            label = "Weekly · all models",
            detail = limitDetail(cc.sevenDayUsedPercentage, cc.sevenDayResetsAt),
            progress = ((cc.sevenDayUsedPercentage ?: 0.0) / 100.0).toFloat(),
        )
        cc.modelScopedQuotas.forEach { quota ->
            UsageProgressRow(
                label = "Weekly · ${quota.displayName}",
                detail = limitDetail(quota.usedPercentage, quota.resetsAtEpochSeconds),
                progress = ((quota.usedPercentage ?: 0.0) / 100.0).toFloat(),
            )
        }
        cc.accountUsageError?.let { error ->
            Text(
                "Could not refresh account limits: $error",
                style = MaterialTheme.typography.labelSmall,
                color = Cosmos.Amber,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Session $${"%.4f".format(cc.sessionCostUsd)}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                color = Cosmos.TextMuted,
            )
            cc.sessionDurationMs?.let {
                Text(formatDuration(it), style = MaterialTheme.typography.labelSmall, color = Cosmos.TextFaint)
            }
        }
    }
}

@Composable
private fun UsageProgressRow(label: String, detail: String, progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Cosmos.TextDim)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
        }
        LiquidProgress(progress = progress.coerceIn(0f, 1f), height = 3.dp)
    }
}

private fun limitDetail(used: Double?, resetsAt: Long?): String = when {
    used == null -> "Not reported"
    resetsAt == null -> "${"%.0f".format(used)}% used"
    else -> "${"%.0f".format(used)}% · resets in ${formatDuration(resetsAt * 1000L - System.currentTimeMillis())}"
}

private fun claudeEffortLabel(effort: String): String = when (effort) {
    "xhigh" -> "Ultracode"
    "max" -> "Max"
    else -> effort.replaceFirstChar(Char::uppercase)
}

private fun formatDuration(milliseconds: Long): String {
    val minutes = milliseconds.coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes / (24 * 60)}d ${(minutes % (24 * 60)) / 60}h"
    }
}

private fun compactTokenCount(n: Int): String = compactTokenCount(n.toLong())

private fun compactTokenCount(n: Long): String = when {
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
    onCancelSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cc = ui.claudeCode
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidButton(
                        "Copy link",
                        compact = true,
                        style = LiquidButtonStyle.Glass,
                        onClick = { clipboard.setText(AnnotatedString(cc.loginUrl)) },
                    )
                    LiquidButton(
                        "Cancel",
                        compact = true,
                        style = LiquidButtonStyle.Glass,
                        onClick = onCancelSignIn,
                    )
                }
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
                    LiquidButton(
                        "Submit",
                        compact = true,
                        enabled = pastedCode.isNotBlank(),
                        onClick = { onSubmitCode(pastedCode); pastedCode = "" },
                    )
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

/**
 * Codex's provisioning/sign-in banner, the counterpart of [ClaudeCodeSetupBanner].
 *
 * The sign-in step is shaped differently from Claude's on purpose, because the flow itself is: Codex uses
 * a device-code grant, so there is no code to paste BACK — the user opens a URL, types the short code the
 * banner displays, and the CLI notices on its own. That removes the text field entirely and means the
 * approval can happen on a completely different device, which is why the code is shown large and
 * selectable rather than tucked into body text.
 */
@Composable
private fun CodexSetupBanner(
    ui: ChatUiState,
    onInstall: () -> Unit,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cx = ui.codex
    val context = LocalContext.current

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.1f)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Set up Codex", style = MaterialTheme.typography.labelLarge, color = Cosmos.Text)
        when {
            cx.installProgress != null -> {
                Text(cx.installProgress, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
            }
            !cx.installed -> {
                Text(
                    if (cx.needsOnlyBinary) {
                        "Downloads the real Codex CLI into the Linux environment you already have. " +
                            "About 105MB to download, 257MB on disk. Needs a network connection."
                    } else {
                        "Codex shares the same Linux environment as Claude Code — install that first " +
                            "from the Claude Code setup, then come back here."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                )
                if (cx.needsOnlyBinary) {
                    LiquidButton("Download Codex", onClick = onInstall, compact = true)
                }
            }
            cx.loginUrl != null -> {
                Text(
                    "Open this page and enter the code below to link your ChatGPT plan. " +
                        "You can do this on another device — Codex will pick it up automatically.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                )
                cx.loginUserCode?.let { code ->
                    Text(
                        code,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFamily),
                        color = Cosmos.Violet3,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidButton(
                        "Open sign-in page",
                        compact = true,
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cx.loginUrl))) } },
                    )
                    LiquidButton(
                        "Cancel",
                        compact = true,
                        style = LiquidButtonStyle.Glass,
                        onClick = onCancelSignIn,
                    )
                }
            }
            !cx.loggedIn -> {
                Text(
                    "Sign in with your ChatGPT Plus, Pro, or Business plan — no separate API billing.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                )
                LiquidButton(if (cx.loginBusy) "Starting…" else "Sign in", compact = true, onClick = onSignIn)
            }
        }
        cx.loginError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Cosmos.Red) }
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
                ChatMode.CODEX -> "What should Codex do?"
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
                ChatMode.CODEX -> "The real Codex CLI, billed to your ChatGPT plan"
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

// ─────────────────────────────── Command picker / input bar ───────────────────────────────

/**
 * Explicit command and engine selector.  This replaces the old tap-to-cycle control: cycling made
 * it possible to select Claude Code, tap once more while composing, and silently send through the
 * locally-selected Qwen provider.  Every row here states both the execution engine and its ready
 * state, so the composer label and the turn runner always agree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandPickerSheet(
    ui: ChatUiState,
    onChoose: (ChatMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF20A0618),
        dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Orion command",
                style = MaterialTheme.typography.titleMedium.copy(brush = Cosmos.TitleGradient),
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Choose how this message runs. Agent engines use your signed-in subscription; cloud and local models remain separate.",
                style = MaterialTheme.typography.labelSmall,
                color = Cosmos.TextMuted,
            )

            CommandPickerRow(
                title = "Auto chat",
                subtitle = ui.selectedProvider?.let { "Cloud/local model: ${ui.selectedModel ?: it.label}" }
                    ?: "Choose a configured cloud or local model",
                mode = ChatMode.CHAT,
                selected = ui.mode == ChatMode.CHAT,
                status = "Default",
                icon = Icons.Outlined.ChatBubbleOutline,
                onClick = { onChoose(ChatMode.CHAT) },
            )
            CommandPickerRow(
                title = "Orion Builder",
                subtitle = "Plans and runs Orion build workflows with the selected provider",
                mode = ChatMode.BUILD,
                selected = ui.mode == ChatMode.BUILD,
                status = "Agent",
                icon = Icons.Outlined.Build,
                onClick = { onChoose(ChatMode.BUILD) },
            )

            Text(
                "Subscription agent engines",
                style = MaterialTheme.typography.labelMedium,
                color = Cosmos.Violet3,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
            CommandPickerRow(
                title = "Claude Code",
                subtitle = when {
                    !ui.claudeCode.installed -> "Install the on-device Claude Code runtime"
                    ui.claudeCode.loggedIn -> "${ui.claudeCode.model ?: "Account default"} · ${claudeEffortLabel(ui.claudeCode.effort)} · full tool access"
                    else -> "Sign in with your Claude Pro, Max, or Team subscription"
                },
                mode = ChatMode.CLAUDE_CODE,
                selected = ui.mode == ChatMode.CLAUDE_CODE,
                status = if (ui.claudeCode.loggedIn) "Ready" else if (ui.claudeCode.installed) "Sign in" else "Install",
                icon = Icons.Outlined.Terminal,
                ready = ui.claudeCode.loggedIn,
                onClick = { onChoose(ChatMode.CLAUDE_CODE) },
            )
            CommandPickerRow(
                title = "Codex",
                subtitle = when {
                    !ui.codex.installed -> "Install the Codex CLI into the shared Linux runtime"
                    ui.codex.loggedIn -> "${ui.codex.model ?: "Account default"} · ${claudeEffortLabel(ui.codex.effort)}"
                    else -> "Sign in with your ChatGPT subscription"
                },
                mode = ChatMode.CODEX,
                selected = ui.mode == ChatMode.CODEX,
                status = if (ui.codex.loggedIn) "Ready" else if (ui.codex.installed) "Sign in" else "Install",
                icon = Icons.Outlined.Terminal,
                ready = ui.codex.loggedIn,
                onClick = { onChoose(ChatMode.CODEX) },
            )

            if (ui.providers.isNotEmpty()) {
                Text(
                    "Configured model sources",
                    style = MaterialTheme.typography.labelMedium,
                    color = Cosmos.Violet3,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
                ui.providers.take(5).forEach { provider ->
                    Text(
                        provider.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = Cosmos.TextDim,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandPickerRow(
    title: String,
    subtitle: String,
    mode: ChatMode,
    selected: Boolean,
    status: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    ready: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Liquid.ShapeSmall)
            .background(if (selected) Cosmos.Violet.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) Cosmos.Violet.copy(alpha = 0.55f) else Cosmos.Hairline, Liquid.ShapeSmall)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = if (selected) Cosmos.Violet3 else Cosmos.TextMuted, modifier = Modifier.size(19.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Cosmos.Text)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(status, style = MaterialTheme.typography.labelSmall, color = if (ready) Cosmos.Green else Cosmos.Violet3)
            if (selected) Icon(Icons.Outlined.Check, null, tint = Cosmos.Green, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun InputBar(
    ui: ChatUiState,
    onInput: (String) -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (ChatAttachment) -> Unit,
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
                    // Both CLI engines are terminal-backed; the label beside the icon is what
                    // distinguishes them, so they intentionally share the glyph.
                    ChatMode.CLAUDE_CODE, ChatMode.CODEX -> Icons.Outlined.Terminal
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
                    ChatMode.CODEX -> "Codex"
                    ChatMode.CHAT -> "Chat"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (accentMode) Cosmos.Violet3 else Cosmos.TextMuted,
            )
        }

        // Attachments are copied into the shared project sandbox, so they work the same for either CLI.
        if (ui.mode.isAgentCli) {
            Icon(
                Icons.Outlined.AttachFile,
                contentDescription = "Attach files",
                tint = Cosmos.TextMuted,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !ui.busy) { haptic(); onAttach() }
                    .padding(6.dp),
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

        val canSend = (ui.input.isNotBlank() || ui.attachments.isNotEmpty()) && !ui.busy
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    msg.parts.joinToString("\n") { it.text }.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Cosmos.Text)
                    }
                    msg.parts.flatMap { it.attachments }.forEach { attachment -> AttachmentPreview(attachment) }
                }
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
                PartType.THINKING -> if (part.text.isNotBlank() || msg.status == MsgStatus.STREAMING) {
                    ThinkingBlock(text = part.text, streaming = msg.status == MsgStatus.STREAMING)
                }
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
private fun AttachmentPreview(attachment: ChatAttachment) {
    val image = attachment.mimeType?.startsWith("image/") == true
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x22000000))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (image) {
            AsyncImage(
                model = File(attachment.localPath),
                contentDescription = attachment.displayName,
                modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(7.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        Text(attachment.displayName, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextDim)
        Text(
            attachment.mimeType ?: "File",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = Cosmos.TextMuted,
        )
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
    onSignIn: () -> Unit,
    onSwitchAccount: () -> Unit,
    onLogout: () -> Unit,
    onSwitchToCodex: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cc = ui.claudeCode
    var confirmAction by remember { mutableStateOf<ClaudeAccountAction?>(null) }

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

            Column(
                Modifier
                    .fillMaxWidth()
                    .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.08f)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("Account", style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
                if (cc.loggedIn) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Outlined.Person, null, tint = Cosmos.Violet3, modifier = Modifier.size(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                cc.email ?: "Claude subscription",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Cosmos.Text,
                            )
                            Text(
                                cc.subscriptionType?.replaceFirstChar(Char::uppercase) ?: "Signed in",
                                style = MaterialTheme.typography.labelSmall,
                                color = Cosmos.TextMuted,
                            )
                        }
                        if (cc.accountActionBusy) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 1.5.dp, color = Cosmos.Violet)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiquidButton(
                            "Switch account",
                            compact = true,
                            style = LiquidButtonStyle.Glass,
                            enabled = !cc.accountActionBusy && !cc.loginBusy,
                            onClick = { confirmAction = ClaudeAccountAction.SWITCH },
                        )
                        LiquidButton(
                            "Log out",
                            compact = true,
                            style = LiquidButtonStyle.Destructive,
                            enabled = !cc.accountActionBusy && !cc.loginBusy,
                            onClick = { confirmAction = ClaudeAccountAction.LOGOUT },
                        )
                    }
                } else {
                    Text(
                        if (cc.installed) "No Claude account is signed in on this device."
                        else "Install the Claude Code runtime to sign in on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Cosmos.TextMuted,
                    )
                    LiquidButton(
                        if (cc.loginBusy) "Starting sign-in…" else "Sign in",
                        compact = true,
                        enabled = cc.installed && !cc.loginBusy && !cc.accountActionBusy,
                        onClick = onSignIn,
                    )
                }
                cc.accountActionError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Cosmos.Red)
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.08f)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Continue in Codex", style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
                Text(
                    "Hands this conversation to Codex with a summary of what has happened so far — useful " +
                        "when this account is out of quota. Both engines work on the same project files.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextFaint,
                )
                LiquidButton(
                    "Switch to Codex",
                    compact = true,
                    style = LiquidButtonStyle.Glass,
                    enabled = !ui.busy,
                    onClick = { onSwitchToCodex(); onDismiss() },
                )
            }

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
                            text = claudeEffortLabel(effort),
                            selected = cc.effort == effort,
                            onClick = { onPick(cc.model, effort, cc.permissionMode) },
                        )
                    }
                }
                Text(
                    "Ultracode is Claude Code's xhigh effort mode; the CLI reports it as xhigh.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextFaint,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Permission mode", style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ClaudeCodeUiState.PERMISSION_MODE_OPTIONS.forEach { mode ->
                        LiquidChip(
                            text = when (mode) {
                                "default" -> "Ask"
                                "acceptEdits" -> "Accept edits"
                                "bypassPermissions" -> "Full access"
                                "dontAsk" -> "Deny prompts"
                                else -> mode
                            },
                            selected = cc.permissionMode == mode,
                            onClick = { onPick(cc.model, cc.effort, mode) },
                        )
                    }
                }
                Text(
                    "Ask and Accept edits show Orion's approval dialog with Allow, Don't allow, and " +
                        "Always allow for this session. Full access runs unattended; Plan stays read-only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextFaint,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    confirmAction?.let { action ->
        val switching = action == ClaudeAccountAction.SWITCH
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(if (switching) "Switch Claude account?" else "Log out of Claude Code?") },
            text = {
                Text(
                    if (switching) {
                        "Claude Code will stop active work, sign out of ${cc.email ?: "the current account"}, and open the sign-in flow for another account."
                    } else {
                        "Claude Code will stop active work and remove this account from Orion. You can sign in again at any time."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAction = null
                    if (switching) onSwitchAccount() else onLogout()
                }) {
                    Text(if (switching) "Switch account" else "Log out", color = Cosmos.Red)
                }
            },
            dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Cancel") } },
        )
    }
}

private enum class ClaudeAccountAction { SWITCH, LOGOUT }

/**
 * Codex's counterpart of [ClaudeCodeOptionsSheet].
 *
 * Also the place the engine handoff is offered from, via [onSwitchToClaude]: switching engines is an
 * account-level decision (the usual trigger being "this account is out of quota"), so it belongs next to
 * the account block rather than buried in the mode toggle's cycle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexOptionsSheet(
    ui: ChatUiState,
    onPick: (model: String?, effort: String, sandbox: String) -> Unit,
    onSignIn: () -> Unit,
    onLogout: () -> Unit,
    onReinstall: () -> Unit,
    onSwitchToClaude: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cx = ui.codex
    var confirmLogout by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF20A0618),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Codex options",
                style = MaterialTheme.typography.titleMedium.copy(brush = Cosmos.TitleGradient),
                fontWeight = FontWeight.Bold,
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.08f)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("Account", style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
                if (cx.loggedIn) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Outlined.Person, null, tint = Cosmos.Violet3, modifier = Modifier.size(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                cx.authMethod ?: "Signed in",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Cosmos.Text,
                            )
                            cx.installedVersion?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
                            }
                        }
                        if (cx.accountActionBusy) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 1.5.dp, color = Cosmos.Violet)
                        }
                    }
                    LiquidButton(
                        "Log out",
                        compact = true,
                        style = LiquidButtonStyle.Destructive,
                        enabled = !cx.accountActionBusy && !cx.loginBusy,
                        onClick = { confirmLogout = true },
                    )
                } else {
                    Text(
                        if (cx.installed) "No ChatGPT account is signed in on this device."
                        else "Download the Codex CLI to sign in on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Cosmos.TextMuted,
                    )
                    LiquidButton(
                        if (cx.loginBusy) "Starting sign-in…" else "Sign in",
                        compact = true,
                        enabled = cx.installed && !cx.loginBusy && !cx.accountActionBusy,
                        onClick = onSignIn,
                    )
                }
                cx.accountActionError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Cosmos.Red)
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.08f)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Continue in Claude Code", style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
                Text(
                    "Hands this conversation to Claude Code with a summary of what has happened so far. " +
                        "Both engines work on the same project files, so nothing on disk changes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextFaint,
                )
                LiquidButton(
                    "Switch to Claude Code",
                    compact = true,
                    style = LiquidButtonStyle.Glass,
                    enabled = !ui.busy,
                    onClick = { onSwitchToClaude(); onDismiss() },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Model", style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CodexUiState.MODEL_OPTIONS.forEach { model ->
                        LiquidChip(
                            text = model ?: "Default",
                            selected = cx.model == model,
                            onClick = { onPick(model, cx.effort, cx.sandbox) },
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
                    CodexUiState.EFFORT_OPTIONS.forEach { effort ->
                        LiquidChip(
                            text = claudeEffortLabel(effort),
                            selected = cx.effort == effort,
                            onClick = { onPick(cx.model, effort, cx.sandbox) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sandbox", style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CodexUiState.SANDBOX_OPTIONS.forEach { sandbox ->
                        LiquidChip(
                            text = when (sandbox) {
                                "danger-full-access" -> "Full access"
                                "workspace-write" -> "Workspace write"
                                "read-only" -> "Read only"
                                else -> sandbox
                            },
                            selected = cx.sandbox == sandbox,
                            onClick = { onPick(cx.model, cx.effort, sandbox) },
                        )
                    }
                }
                Text(
                    "Full access is the working default on Android: Codex enforces the stricter modes " +
                        "with Linux kernel features the phone's chroot may not provide, and when they're " +
                        "missing every command fails. Files stay limited to what Orion itself can reach.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextFaint,
                )
            }

            if (cx.installed) {
                LiquidButton(
                    "Re-download Codex CLI",
                    compact = true,
                    style = LiquidButtonStyle.Glass,
                    enabled = cx.installProgress == null,
                    onClick = onReinstall,
                )
                cx.installProgress?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out of Codex?") },
            text = {
                Text(
                    "Codex will stop active work and remove this account from Orion. Stored Codex " +
                        "conversation threads are cleared too, since they belong to this account.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) {
                    Text("Log out", color = Cosmos.Red)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
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
