package com.legion.orion.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.database.ChatEntity
import com.legion.orion.core.database.ProviderEntity
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.MonoFamily
import com.legion.orion.core.design.glass
import com.legion.orion.data.chat.MsgRole
import com.legion.orion.data.chat.MsgStatus
import com.legion.orion.data.chat.PartType
import com.legion.orion.data.chat.ToolView
import com.legion.orion.data.chat.UiMessage
import com.legion.orion.feature.editor.EditorScreen
import com.legion.orion.feature.preview.PreviewScreen
import com.legion.orion.inference.EngineState
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(vm: ChatViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val chats by vm.chats.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size, messages.lastOrNull()) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                chats = chats,
                activeChatId = ui.activeChatId,
                onNew = {
                    vm.newChat()
                    scope.launch { drawerState.close() }
                },
                onOpen = {
                    vm.openChat(it)
                    scope.launch { drawerState.close() }
                },
                onDelete = vm::deleteChat,
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            CommandHeader(
                ui = ui,
                engine = engine,
                onOpenHistory = { scope.launch { drawerState.open() } },
                onChooseProvider = vm::chooseProvider,
                onNewChat = vm::newChat,
                onMode = vm::setMode,
            )
            CommandContentTabs(ui.contentTab, vm::setContentTab)

            ui.error?.let { err ->
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                        .glass(RoundedCornerShape(10.dp), fill = Color(0x33FF505A), border = Color(0x55FF505A))
                        .padding(12.dp),
                ) { Text(err, color = Cosmos.Red, style = MaterialTheme.typography.bodySmall) }
            }
            ui.notice?.let { notice ->
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                        .glass(RoundedCornerShape(10.dp), fill = Color(0x2235D69B), border = Color(0x5535D69B))
                        .padding(12.dp),
                ) { Text(notice, color = Cosmos.Green, style = MaterialTheme.typography.bodySmall) }
            }

            when (ui.contentTab) {
                CommandContentTab.CHAT -> {
                    if (messages.isEmpty()) {
                        EmptyChat(ui.mode, Modifier.weight(1f))
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(messages, key = { it.id }) { msg -> MessageView(msg, vm) }
                        }
                    }
                }
                CommandContentTab.PREVIEW -> Box(Modifier.weight(1f).fillMaxWidth()) { PreviewScreen() }
                CommandContentTab.CODE -> Box(Modifier.weight(1f).fillMaxWidth()) { EditorScreen() }
            }

            QuickActions(
                ui.mode,
                onPick = {
                    vm.onInput(it)
                    if (ui.mode != ChatMode.BUILD && it.startsWith("Generate")) vm.setMode(ChatMode.BUILD)
                },
            )
            InputBar(ui.input, ui.busy, ui.mode, vm::onInput, vm::send, vm::stop)
        }
    }
}

@Composable
private fun CommandContentTabs(selected: CommandContentTab, onSelect: (CommandContentTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            CommandContentTab.CHAT to "Chat",
            CommandContentTab.PREVIEW to "Preview",
            CommandContentTab.CODE to "Code",
        ).forEach { (tab, label) ->
            Box(
                Modifier
                    .weight(1f)
                    .glass(
                        RoundedCornerShape(12.dp),
                        fill = if (selected == tab) Color(0x33A88CFF) else Cosmos.GlassFill,
                        border = if (selected == tab) Color(0x66A88CFF) else Cosmos.HairlineSoft,
                    )
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected == tab) Cosmos.Violet3 else Cosmos.TextMuted)
            }
        }
    }
}

@Composable
private fun CommandHeader(
    ui: ChatUiState,
    engine: EngineState,
    onOpenHistory: () -> Unit,
    onChooseProvider: (ProviderEntity) -> Unit,
    onNewChat: () -> Unit,
    onMode: (ChatMode) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Filled.Menu, "Chat history", tint = Cosmos.TextDim)
            }
            Column(Modifier.weight(1f)) {
                Text("Orion Command", style = MaterialTheme.typography.titleLarge.copy(brush = Cosmos.TitleGradient))
                Text(
                    if (ui.mode == ChatMode.BUILD) "Build, preview, edit, publish" else "Ask, reason, and route work",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                )
            }
            ProviderPicker(ui.providers, ui.selectedProvider, ui.selectedModel, onChooseProvider, Modifier.widthIn(max = 210.dp))
            IconButton(onClick = onNewChat) {
                Icon(Icons.Filled.Add, "New chat", tint = Cosmos.Violet)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("Chat", ui.mode == ChatMode.CHAT) { onMode(ChatMode.CHAT) }
            ModeChip("Build", ui.mode == ChatMode.BUILD) { onMode(ChatMode.BUILD) }
            if (ui.mode == ChatMode.BUILD && ui.projectName != null) {
                Text("- ${ui.projectName}", style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
            }
        }
        LiveStatsStrip(engine, ui.liveTokens, ui.liveTokensPerSecond)
    }
}

@Composable
private fun ChatHistoryDrawer(
    chats: List<ChatEntity>,
    activeChatId: String?,
    onNew: () -> Unit,
    onOpen: (ChatEntity) -> Unit,
    onDelete: (ChatEntity) -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = Color(0xF004030A), drawerContentColor = Cosmos.Text) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Chat history", style = MaterialTheme.typography.headlineSmall.copy(brush = Cosmos.TitleGradient))
            Box(
                Modifier.fillMaxWidth()
                    .glass(RoundedCornerShape(12.dp), Color(0x1FA88CFF), Color(0x47A88CFF))
                    .clickable { onNew() }
                    .padding(12.dp),
            ) {
                Text("New Orion command", color = Cosmos.Violet3, style = MaterialTheme.typography.labelLarge)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(chats, key = { it.id }) { chat ->
                    val selected = chat.id == activeChatId
                    Row(
                        Modifier.fillMaxWidth()
                            .glass(
                                RoundedCornerShape(12.dp),
                                fill = if (selected) Color(0x2AA88CFF) else Cosmos.GlassFill,
                                border = if (selected) Color(0x66A88CFF) else Cosmos.HairlineSoft,
                            )
                            .clickable { onOpen(chat) }
                            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(chat.title.ifBlank { "Untitled chat" }, color = Cosmos.TextDim, maxLines = 1)
                            Text(chat.mode.uppercase(), color = Cosmos.TextMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { onDelete(chat) }) {
                            Icon(Icons.Filled.Delete, "Delete chat", tint = Cosmos.TextMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveStatsStrip(engine: EngineState, tokens: Int, tps: Double) {
    if (engine.status == EngineState.Status.IDLE && tokens == 0) return
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .glass(RoundedCornerShape(12.dp), fill = Color(0x22000000), border = Cosmos.HairlineSoft)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            engine.status.name,
            style = MaterialTheme.typography.labelMedium,
            color = when (engine.status) {
                EngineState.Status.READY -> Cosmos.Green
                EngineState.Status.ERROR -> Cosmos.Red
                EngineState.Status.GENERATING -> Cosmos.Amber
                else -> Cosmos.TextMuted
            },
        )
        engine.backend?.let {
            Text(it.configValue.uppercase(), style = MaterialTheme.typography.labelSmall, color = Cosmos.Violet3)
        }
        if (tokens > 0) {
            Text("$tokens chunks", style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
            Text("%.1f/s".format(tps), style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
        }
        engine.modelDir?.substringAfterLast('/')?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted, maxLines = 1)
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .glass(
                RoundedCornerShape(14.dp),
                fill = if (selected) Color(0x33A88CFF) else Color.Transparent,
                border = if (selected) Color(0x66A88CFF) else Cosmos.Hairline,
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) Cosmos.Violet3 else Cosmos.TextMuted)
    }
}

@Composable
private fun QuickActions(mode: ChatMode, onPick: (String) -> Unit) {
    val suggestions = if (mode == ChatMode.BUILD) {
        listOf("Create a landing page", "Build a TODO app", "Generate an image of a nebula", "Make an Android hello-world")
    } else {
        listOf("Explain transformers", "Write a haiku", "Generate an image of a cat", "Summarize this idea")
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        suggestions.forEach { s ->
            Box(
                Modifier.glass(RoundedCornerShape(14.dp)).clickable { onPick(s) }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(s, style = MaterialTheme.typography.labelMedium, color = Cosmos.TextDim) }
        }
    }
}

@Composable
private fun EmptyChat(mode: ChatMode, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Orion", style = MaterialTheme.typography.displaySmall.copy(brush = Cosmos.TitleGradient))
        Text(
            if (mode == ChatMode.BUILD) "Describe an app, edit code, open preview, or queue a media job."
            else "Ask anything. Switch to Build for tools, previews, code edits, and media.",
            style = MaterialTheme.typography.bodyMedium,
            color = Cosmos.TextMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ProviderPicker(
    providers: List<ProviderEntity>,
    selected: ProviderEntity?,
    model: String,
    onChoose: (ProviderEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.glass(RoundedCornerShape(12.dp)).clickable { expanded = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(selected?.label ?: "Select", style = MaterialTheme.typography.labelLarge, color = Cosmos.Text, maxLines = 1)
                if (model.isNotEmpty()) Text(model, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted, maxLines = 1)
            }
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = Cosmos.TextMuted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { p ->
                DropdownMenuItem(text = { Text(p.label) }, onClick = { onChoose(p); expanded = false })
            }
        }
    }
}

@Composable
private fun MessageView(msg: UiMessage, vm: ChatViewModel) {
    val isUser = msg.role == MsgRole.USER
    if (isUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.widthIn(max = 320.dp)
                    .glass(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp), Color(0x24A88CFF), Color(0x47A88CFF))
                    .padding(12.dp),
            ) {
                Text(
                    msg.parts.firstOrNull { it.type == PartType.TEXT }?.text.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cosmos.TextDim,
                )
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        msg.parts.forEach { part ->
            when (part.type) {
                PartType.THINKING -> ThinkingBlock(part.text, msg.status)
                PartType.TOOL_CALL -> part.tool?.let { ToolCard(it, vm) }
                else -> if (part.text.isNotEmpty()) {
                    Box(
                        Modifier.widthIn(max = 340.dp)
                            .glass(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp), Cosmos.GlassFill, Cosmos.Hairline)
                            .padding(12.dp),
                    ) {
                        Text(part.text, style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextDim)
                    }
                }
            }
        }
        if (msg.status == MsgStatus.STREAMING && msg.parts.none { it.type == PartType.TEXT || it.type == PartType.TOOL_CALL }) {
            Text("...", color = Cosmos.TextMuted, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun ToolCard(tool: ToolView, vm: ChatViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val tone = when {
        tool.status == "running" -> Color(0x33A88CFF)
        tool.status == "done" -> Color(0x3354D68A)
        else -> Color(0x33FF505A)
    }
    Box(
        Modifier.fillMaxWidth()
            .glass(RoundedCornerShape(10.dp), fill = tone, border = Color(0x44A88CFF))
            .clickable { expanded = !expanded }
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Build, null, tint = Cosmos.Violet, modifier = Modifier.padding(1.dp))
                Text(tool.name, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily), color = Cosmos.Violet3, modifier = Modifier.weight(1f))
                when (tool.status) {
                    "running" -> CircularProgressIndicator(Modifier.size(16.dp), color = Cosmos.Violet, strokeWidth = 2.dp)
                    "done" -> Icon(Icons.Filled.Check, "done", tint = Cosmos.Green)
                    else -> Icon(Icons.Filled.Close, "error", tint = Cosmos.Red)
                }
            }
            if (tool.args.isNotBlank() && tool.args != "{}") {
                Text(tool.args, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted, maxLines = if (expanded) 20 else 1)
            }
            if (expanded && !tool.result.isNullOrBlank()) {
                Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(8.dp), Color(0x33000000), Cosmos.HairlineSoft).padding(8.dp)) {
                    Text(tool.result, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily), color = Cosmos.TextMuted, maxLines = 30)
                }
            }
            if (tool.status == "done" && tool.snapshotRef != null) {
                Box(
                    Modifier.glass(RoundedCornerShape(8.dp)).clickable { vm.rollback(tool) }.padding(horizontal = 10.dp, vertical = 5.dp),
                ) { Text("Undo this change", style = MaterialTheme.typography.labelMedium, color = Cosmos.Amber) }
            }
        }
    }
}

@Composable
private fun ThinkingBlock(text: String, status: String) {
    var expanded by remember(status) { mutableStateOf(status == MsgStatus.STREAMING) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "Thinking - expanded" else "Thinking - collapsed", style = MaterialTheme.typography.labelMedium, color = Cosmos.Violet)
        }
        AnimatedVisibility(visible = expanded) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp).glass(RoundedCornerShape(8.dp), Color(0x33000000), Cosmos.HairlineSoft).padding(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily), color = Cosmos.TextMuted)
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    busy: Boolean,
    mode: ChatMode,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
            .glass(RoundedCornerShape(24.dp), fill = Color(0x66000000), border = Cosmos.Hairline)
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (mode == ChatMode.BUILD) "Build, generate, or ask..." else "Message Orion...", color = Cosmos.TextFaint) },
            maxLines = 5,
            textStyle = MaterialTheme.typography.bodyLarge,
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
        val shape = RoundedCornerShape(20.dp)
        Box(
            Modifier.padding(end = 2.dp).clip(shape)
                .background(if (busy) Color(0x33FF505A) else Cosmos.Violet)
                .clickable { if (busy) onStop() else onSend() }
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (busy) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                contentDescription = if (busy) "Stop" else "Send",
                tint = if (busy) Cosmos.Red else Color.White,
            )
        }
    }
}
