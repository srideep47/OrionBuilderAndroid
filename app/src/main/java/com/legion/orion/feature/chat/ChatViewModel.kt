package com.legion.orion.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.agent.AppAgentTools
import com.legion.orion.core.database.ChatEntity
import com.legion.orion.core.database.ProviderEntity
import com.legion.orion.core.datastore.SettingsRepository
import com.legion.orion.data.chat.ChatRepository
import com.legion.orion.data.chat.MsgRole
import com.legion.orion.data.chat.MsgStatus
import com.legion.orion.data.chat.PartType
import com.legion.orion.data.chat.ToolView
import com.legion.orion.data.chat.UiMessage
import com.legion.orion.data.chat.UiPart
import com.legion.orion.data.project.ActiveProjectStore
import com.legion.orion.data.project.ProjectManager
import com.legion.orion.data.providers.ProviderRepository
import com.legion.orion.data.providers.ProviderResolver
import com.legion.orion.data.providers.ProviderType
import com.legion.orion.data.providers.ResolveResult
import com.legion.orion.engine.agent.AgentConfig
import com.legion.orion.engine.agent.AgentEvent
import com.legion.orion.engine.agent.AgentLoop
import com.legion.orion.engine.agent.SnapshotRef
import com.legion.orion.engine.agent.SystemPrompts
import com.legion.orion.engine.agent.ToolRegistry
import com.legion.orion.engine.agent.tools.DefaultTools
import com.legion.orion.engine.providers.ChatMessage
import com.legion.orion.engine.providers.ChatRequest
import com.legion.orion.engine.providers.StreamEvent
import com.legion.orion.inference.EngineState
import com.legion.orion.inference.InferenceController
import com.legion.orion.infra.git.GitSnapshotter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val SYSTEM_PROMPT = "You are Orion, a concise and helpful AI assistant."
private const val COMPACTION_SYSTEM_PROMPT =
    "You compact AI coding-agent conversations. Preserve concrete requirements, decisions, file paths, commands, errors, tool results, and unresolved tasks. Do not invent details."

/** Unified command modes: plain chat, or agentic build (tools enabled). */
enum class ChatMode { CHAT, BUILD }

data class StreamingMessage(
    val messageId: String,
    val parts: List<UiPart> = emptyList(),
)

data class ChatUiState(
    val providers: List<ProviderEntity> = emptyList(),
    val selectedProvider: ProviderEntity? = null,
    val selectedModel: String = "",
    val input: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val mode: ChatMode = ChatMode.CHAT,
    val projectName: String? = null,
    val activeChatId: String? = null,
    val liveTokens: Int = 0,
    val liveTokensPerSecond: Double = 0.0,
    val contentTab: CommandContentTab = CommandContentTab.CHAT,
    val contextUsedTokens: Int = 0,
    val contextWindowTokens: Int = 32_768,
    val modelSheetModels: List<String> = emptyList(),
)

enum class CommandContentTab { CHAT, PREVIEW, CODE }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    private val providerRepo: ProviderRepository,
    private val resolver: ProviderResolver,
    private val settings: SettingsRepository,
    private val p2pCoordinator: com.legion.orion.p2p.P2pCoordinator,
    private val projects: ProjectManager,
    private val activeProject: ActiveProjectStore,
    private val appAgentTools: AppAgentTools,
    private val controller: InferenceController,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState())
    val ui = _ui.asStateFlow()

    private val chatIdFlow = MutableStateFlow<String?>(null)
    private val streamingFlow = MutableStateFlow<StreamingMessage?>(null)

    private val conversation = mutableListOf<ChatMessage>()
    private var streamJob: Job? = null
    private var localSessionChatId: String? = null
    private var localSessionPrimed = false

    private val registry by lazy { ToolRegistry(DefaultTools.list() + appAgentTools.list()) }
    private var projectId: String? = null

    val chats: StateFlow<List<ChatEntity>> = chatRepo.observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val engineState: StateFlow<EngineState> = controller.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), controller.state.value)

    val messages: StateFlow<List<UiMessage>> =
        chatIdFlow.flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else chatRepo.observeMessages(id).combine(streamingFlow) { stored, streaming ->
                if (streaming == null) stored
                else stored.filter { it.id != streaming.messageId } + streaming.toUiMessage()
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            providerRepo.seedDefaultsIfEmpty()
            val providers = providerRepo.observeProviders().first()
            val selectedId = settings.selectedProviderId.firstOrNull()
            val selectedModel = settings.selectedModelId.firstOrNull()
            val chosen = providers.firstOrNull { it.id == selectedId } ?: providers.firstOrNull()
            val model = selectedModel ?: chosen?.let { providerRepo.modelsFor(it.id).firstOrNull()?.modelId } ?: ""
            _ui.value = _ui.value.copy(providers = providers, selectedProvider = chosen, selectedModel = model)

            val recent = chatRepo.observeChats().first().firstOrNull()
            if (recent != null) {
                chatIdFlow.value = recent.id
                projectId = recent.projectId
                activeProject.set(recent.projectId)
                _ui.value = _ui.value.copy(activeChatId = recent.id)
                rebuildConversationFrom(recent.id)
                localSessionChatId = recent.id
                localSessionPrimed = conversation.isEmpty()
                if (!localSessionPrimed) runCatching { controller.reset() }
            }
        }

        viewModelScope.launch {
            combine(providerRepo.observeProviders(), p2pCoordinator.peers) { stored, peers ->
                stored + peers
                    .filter { it.isTrusted && it.status == "online" && it.computeAvailable }
                    .map { ProviderResolver.peerProviderEntity(it) }
            }.collect { all ->
                val current = _ui.value
                val reselected = current.selectedProvider?.let { sel -> all.firstOrNull { it.id == sel.id } }
                _ui.value = current.copy(
                    providers = all,
                    selectedProvider = reselected ?: current.selectedProvider ?: all.firstOrNull(),
                )
            }
        }
    }

    fun onInput(text: String) { _ui.value = _ui.value.copy(input = text) }

    fun setMode(mode: ChatMode) {
        _ui.value = _ui.value.copy(mode = mode)
        if (mode == ChatMode.BUILD && projectId == null) viewModelScope.launch { ensureProject() }
    }

    fun setContentTab(tab: CommandContentTab) {
        _ui.value = _ui.value.copy(contentTab = tab)
        if (tab != CommandContentTab.CHAT && projectId == null) viewModelScope.launch { ensureProject() }
    }

    fun selectProvider(provider: ProviderEntity, model: String) {
        _ui.value = _ui.value.copy(selectedProvider = provider, selectedModel = model, error = null)
        viewModelScope.launch { settings.setSelectedProvider(provider.id, model) }
    }

    fun chooseProvider(provider: ProviderEntity) {
        viewModelScope.launch {
            val model = if (provider.type == com.legion.orion.data.providers.ProviderType.PEER) {
                "remote-peer"
            } else {
                providerRepo.modelsFor(provider.id).firstOrNull()?.modelId ?: ""
            }
            selectProvider(provider, model)
        }
    }

    fun send() {
        val state = _ui.value
        val provider = state.selectedProvider ?: run {
            _ui.value = state.copy(error = "Select a provider first"); return
        }
        val text = state.input.trim()
        if (text.isEmpty() || state.busy) return

        viewModelScope.launch {
            _ui.value = state.copy(input = "", busy = true, error = null, notice = null, liveTokens = 0, liveTokensPerSecond = 0.0)
            val chatId = chatIdFlow.value ?: chatRepo.createChat(text.take(40), if (state.mode == ChatMode.BUILD) "build" else "ask")
                .also {
                    chatIdFlow.value = it.id
                    _ui.value = _ui.value.copy(activeChatId = it.id)
                }.id
            chatRepo.addUserMessage(chatId, text)
            conversation.add(ChatMessage(ChatMessage.Role.USER, text))

            val resolved = when (val r = resolver.resolve(provider)) {
                is ResolveResult.MissingKey -> { _ui.value = _ui.value.copy(busy = false, error = "Add an API key for ${provider.label} in Settings"); return@launch }
                is ResolveResult.Misconfigured -> { _ui.value = _ui.value.copy(busy = false, error = r.reason); return@launch }
                is ResolveResult.Ready -> r.provider
            }

            refreshContextUsage(provider)
            if (state.mode == ChatMode.BUILD) runAgent(chatId, provider, resolved)
            else runChat(chatId, provider, resolved)
        }
    }

    // ── Plain chat ──

    private suspend fun runChat(
        chatId: String,
        providerEntity: ProviderEntity,
        provider: com.legion.orion.engine.providers.ChatProvider,
    ) {
        val assistantId = chatRepo.beginAssistantMessage(chatId)
        streamingFlow.value = StreamingMessage(assistantId)
        maybeCompactConversation(chatId, providerEntity, provider)
        val useLocalSessionHistory =
            providerEntity.type == ProviderType.MNN_LOCAL &&
                localSessionChatId == chatId &&
                localSessionPrimed
        val request = ChatRequest(
            model = _ui.value.selectedModel,
            messages = conversation.toList(),
            system = SYSTEM_PROMPT,
            preferSessionHistory = useLocalSessionHistory,
        )
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val textBuf = StringBuilder()
            val thinkBuf = StringBuilder()
            val startedAt = System.nanoTime()
            var chunks = 0
            var errorMsg: String? = null
            try {
                provider.stream(request).collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            chunks++
                            textBuf.append(event.text)
                            pushChatParts(assistantId, textBuf, thinkBuf)
                            pushLiveStats(chunks, startedAt)
                        }
                        is StreamEvent.ThinkingDelta -> {
                            chunks++
                            thinkBuf.append(event.text)
                            pushChatParts(assistantId, textBuf, thinkBuf)
                            pushLiveStats(chunks, startedAt)
                        }
                        is StreamEvent.Error -> errorMsg = event.message
                        else -> {}
                    }
                }
            } catch (c: CancellationException) {
                withContext(NonCancellable) { finalizeChat(chatId, assistantId, textBuf, thinkBuf, MsgStatus.CANCELLED, null) }
                throw c
            } catch (t: Throwable) {
                errorMsg = t.message ?: "stream failed"
            }
            val status = if (errorMsg == null) MsgStatus.COMPLETE else MsgStatus.ERROR
            finalizeChat(chatId, assistantId, textBuf, thinkBuf, status, errorMsg)
            if (providerEntity.type == ProviderType.MNN_LOCAL) {
                localSessionChatId = chatId
                localSessionPrimed = useLocalSessionHistory && errorMsg == null
            }
        }
    }

    private fun pushChatParts(id: String, text: StringBuilder, think: StringBuilder) {
        val parts = buildList {
            if (think.isNotEmpty()) add(UiPart(PartType.THINKING, think.toString()))
            if (text.isNotEmpty()) add(UiPart(PartType.TEXT, text.toString()))
        }
        streamingFlow.value = StreamingMessage(id, parts)
    }

    private suspend fun finalizeChat(
        chatId: String, assistantId: String, textBuf: StringBuilder, thinkBuf: StringBuilder, status: String, errorMsg: String?,
    ) {
        chatRepo.finishAssistantMessage(chatId, assistantId, textBuf.toString(), thinkBuf.toString(), status)
        if (textBuf.isNotEmpty()) conversation.add(ChatMessage(ChatMessage.Role.ASSISTANT, textBuf.toString()))
        streamingFlow.value = null
        _ui.value = _ui.value.copy(
            busy = false,
            error = errorMsg,
            contextUsedTokens = estimateTokens(conversation),
        )
    }

    // ── Agentic build (tools) ──

    private suspend fun runAgent(
        chatId: String, providerEntity: ProviderEntity, provider: com.legion.orion.engine.providers.ChatProvider,
    ) {
        val pid = projectId ?: projects.createProject("Playground").also { projectId = it.id }.id
        maybeCompactConversation(chatId, providerEntity, provider)
        activeProject.set(pid)
        chatRepo.bindChatProject(chatId, pid, "build")
        _ui.value = _ui.value.copy(projectName = projects.observeProjects().first().firstOrNull { it.id == pid }?.name)
        val dir = projects.dirFor(pid)
        val window = providerRepo.modelsFor(providerEntity.id).firstOrNull()?.contextWindow ?: 32_768
        val maxIterations = settings.maxAgentIterations.first()
        val loop = AgentLoop(
            provider = provider,
            registry = registry,
            snapshotter = GitSnapshotter(dir),
            config = AgentConfig(
                model = _ui.value.selectedModel,
                contextWindowTokens = window,
                maxIterations = maxIterations,
            ),
            projectDir = dir,
            systemPrompt = SystemPrompts.full(),
        )

        val assistantId = chatRepo.beginAssistantMessage(chatId)
            val parts = mutableListOf<UiPart>()
            val toolIndex = mutableMapOf<String, Int>()
            streamingFlow.value = StreamingMessage(assistantId, parts.toList())

        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            var errorMsg: String? = null
            val startedAt = System.nanoTime()
            var chunks = 0
            try {
                loop.run(chatId = pid, history = conversation.toList()).collect { ev ->
                    when (ev) {
                        is AgentEvent.TextDelta -> {
                            chunks++
                            appendText(parts, ev.text)
                            pushLiveStats(chunks, startedAt)
                        }
                        is AgentEvent.ThinkingDelta -> {
                            chunks++
                            appendThinking(parts, ev.text)
                            pushLiveStats(chunks, startedAt)
                        }
                        is AgentEvent.ToolStarted -> {
                            parts.add(UiPart(PartType.TOOL_CALL, "", ToolView(ev.name, ev.argsJson, "running")))
                            toolIndex[ev.id] = parts.lastIndex
                        }
                        is AgentEvent.ToolFinished -> toolIndex[ev.id]?.let { idx ->
                            val prev = parts[idx].tool
                            parts[idx] = UiPart(
                                PartType.TOOL_CALL, "",
                                ToolView(
                                    name = prev?.name ?: ev.name,
                                    args = prev?.args ?: "",
                                    status = if (ev.result.isError) "error" else "done",
                                    result = ev.result.content,
                                    snapshotRef = ev.snapshot?.ref,
                                    snapshotCommit = ev.snapshot?.commit,
                                ),
                            )
                        }
                        is AgentEvent.Failed -> errorMsg = ev.message
                        else -> {}
                    }
                    streamingFlow.value = StreamingMessage(assistantId, parts.toList())
                }
            } catch (c: CancellationException) {
                withContext(NonCancellable) { chatRepo.finishAssistantParts(chatId, assistantId, parts.toList(), MsgStatus.CANCELLED) }
                streamingFlow.value = null
                _ui.value = _ui.value.copy(busy = false)
                throw c
            } catch (t: Throwable) {
                errorMsg = t.message ?: "agent run failed"
            }
            val status = if (errorMsg == null) MsgStatus.COMPLETE else MsgStatus.ERROR
            chatRepo.finishAssistantParts(chatId, assistantId, parts.toList(), status)
            val assistantText = parts.filter { it.type == PartType.TEXT }.joinToString("\n") { it.text }
            if (assistantText.isNotEmpty()) conversation.add(ChatMessage(ChatMessage.Role.ASSISTANT, assistantText))
            streamingFlow.value = null
            _ui.value = _ui.value.copy(busy = false, error = errorMsg)
        }
    }

    private fun appendText(parts: MutableList<UiPart>, delta: String) {
        val last = parts.lastOrNull()
        if (last != null && last.type == PartType.TEXT) parts[parts.lastIndex] = last.copy(text = last.text + delta)
        else parts.add(UiPart(PartType.TEXT, delta))
    }

    private fun appendThinking(parts: MutableList<UiPart>, delta: String) {
        val last = parts.lastOrNull()
        if (last != null && last.type == PartType.THINKING) parts[parts.lastIndex] = last.copy(text = last.text + delta)
        else parts.add(UiPart(PartType.THINKING, delta))
    }

    fun rollback(tool: ToolView) {
        val ref = tool.snapshotRef ?: return
        val commit = tool.snapshotCommit ?: return
        val pid = projectId ?: return
        viewModelScope.launch {
            runCatching { GitSnapshotter(projects.dirFor(pid)).restore(SnapshotRef(ref, commit)) }
                .onFailure { _ui.value = _ui.value.copy(error = "Rollback failed: ${it.message}") }
        }
    }

    fun stop() { streamJob?.cancel() }

    /** Re-runs the last user turn as a fresh request (new messages appended). */
    fun retryLast() {
        if (_ui.value.busy) return
        val lastUser = conversation.lastOrNull { it.role == ChatMessage.Role.USER } ?: return
        _ui.value = _ui.value.copy(input = lastUser.content)
        send()
    }

    /** Loads the model list for a provider so the model sheet can show choices. */
    fun loadModelsFor(provider: ProviderEntity) {
        viewModelScope.launch {
            val models = if (provider.type == ProviderType.PEER) listOf("remote-peer")
            else providerRepo.modelsFor(provider.id).map { it.modelId }
            _ui.value = _ui.value.copy(modelSheetModels = models)
        }
    }

    private suspend fun refreshContextUsage(providerEntity: ProviderEntity) {
        val window = providerRepo.modelsFor(providerEntity.id).firstOrNull()?.contextWindow ?: 32_768
        _ui.value = _ui.value.copy(
            contextUsedTokens = estimateTokens(conversation),
            contextWindowTokens = window,
        )
    }

    fun newChat() {
        streamJob?.cancel()
        conversation.clear()
        viewModelScope.launch {
            runCatching { controller.reset() }
            val chat = chatRepo.createChat("New chat")
            chatIdFlow.value = chat.id
            projectId = null
            activeProject.set(null)
            localSessionChatId = chat.id
            localSessionPrimed = true
            _ui.value = _ui.value.copy(
                activeChatId = chat.id,
                error = null,
                notice = null,
                projectName = null,
                liveTokens = 0,
                liveTokensPerSecond = 0.0,
                contentTab = CommandContentTab.CHAT,
            )
        }
    }

    fun openChat(chat: ChatEntity) {
        streamJob?.cancel()
        streamingFlow.value = null
        chatIdFlow.value = chat.id
        projectId = chat.projectId
        activeProject.set(chat.projectId)
        _ui.value = _ui.value.copy(
            activeChatId = chat.id,
            mode = if (chat.mode == "build" || chat.mode == "agent") ChatMode.BUILD else ChatMode.CHAT,
            projectName = null,
            notice = null,
        )
        viewModelScope.launch {
            chat.projectId?.let { pid ->
                _ui.value = _ui.value.copy(projectName = projects.get(pid)?.name)
            }
            rebuildConversationFrom(chat.id)
            runCatching { controller.reset() }
            localSessionChatId = chat.id
            localSessionPrimed = conversation.isEmpty()
        }
    }

    fun deleteChat(chat: ChatEntity) {
        viewModelScope.launch {
            chatRepo.deleteChat(chat.id)
            if (chatIdFlow.value == chat.id) {
                conversation.clear()
                val next = chatRepo.observeChats().first().firstOrNull { it.id != chat.id }
                chatIdFlow.value = next?.id
                _ui.value = _ui.value.copy(activeChatId = next?.id)
                runCatching { controller.reset() }
                if (next == null) {
                    localSessionChatId = null
                    localSessionPrimed = false
                } else {
                    rebuildConversationFrom(next.id)
                    localSessionChatId = next.id
                    localSessionPrimed = conversation.isEmpty()
                }
            }
        }
    }

    private suspend fun ensureProject() {
        val existing = projects.observeProjects().first().firstOrNull()
        val project = existing ?: projects.createProject("Playground")
        projectId = project.id
        activeProject.set(project.id)
        chatIdFlow.value?.let { chatRepo.bindChatProject(it, project.id, "build") }
        _ui.value = _ui.value.copy(projectName = project.name)
    }

    private suspend fun rebuildConversationFrom(chatId: String) {
        conversation.clear()
        val chat = chatRepo.getChat(chatId)
        val stored = chatRepo.observeMessages(chatId).first()
        val keepRecent = settings.compactionKeepRecentTurns.first()
        val promptMessages = if (!chat?.summary.isNullOrBlank()) {
            conversation.add(ChatMessage(ChatMessage.Role.USER, compactedSummaryMessage(chat!!.summary!!)))
            stored.takeLast((keepRecent * 2).coerceAtLeast(2))
        } else {
            stored
        }
        promptMessages.forEach { msg ->
            val text = msg.parts.filter { it.type == PartType.TEXT }.joinToString("") { it.text }
            if (text.isNotEmpty()) {
                val role = if (msg.role == MsgRole.ASSISTANT) ChatMessage.Role.ASSISTANT else ChatMessage.Role.USER
                conversation.add(ChatMessage(role, text))
            }
        }
    }

    private suspend fun maybeCompactConversation(
        chatId: String,
        providerEntity: ProviderEntity,
        provider: com.legion.orion.engine.providers.ChatProvider,
    ): Int {
        if (!settings.compactionEnabled.first()) return 0
        val contextWindow = providerRepo.modelsFor(providerEntity.id).firstOrNull()?.contextWindow ?: 32_768
        val threshold = settings.compactionThresholdPercent.first().coerceIn(50, 95) / 100.0
        val budget = (contextWindow * threshold).toInt()
        if (estimateTokens(conversation) <= budget) return 0

        val keepRecent = settings.compactionKeepRecentTurns.first().coerceIn(2, 20) * 2
        if (conversation.size <= keepRecent + 2) return 0
        val oldMessages = conversation.dropLast(keepRecent)
        val recentMessages = conversation.takeLast(keepRecent)
        val previousSummary = chatRepo.getChat(chatId)?.summary
        val summary = summarizeForCompaction(provider, previousSummary, oldMessages)
        conversation.clear()
        conversation.add(ChatMessage(ChatMessage.Role.USER, compactedSummaryMessage(summary)))
        conversation.addAll(recentMessages)
        chatRepo.updateSummary(chatId, summary)
        if (providerEntity.type == ProviderType.MNN_LOCAL) {
            runCatching { controller.reset() }
            localSessionChatId = chatId
            localSessionPrimed = false
        }
        if (settings.compactionNotify.first()) {
            _ui.value = _ui.value.copy(notice = "Context compacted (${oldMessages.size} older messages)")
        }
        return oldMessages.size
    }

    private suspend fun summarizeForCompaction(
        provider: com.legion.orion.engine.providers.ChatProvider,
        previousSummary: String?,
        messages: List<ChatMessage>,
    ): String {
        val transcript = buildString {
            previousSummary?.takeIf { it.isNotBlank() }?.let {
                appendLine("Previous compacted summary:")
                appendLine(it)
                appendLine()
            }
            appendLine("Conversation segment to compact:")
            messages.forEach { msg ->
                appendLine("${msg.role.name.lowercase()}: ${msg.content.take(6000)}")
                if (msg.toolCalls.isNotEmpty()) appendLine("tool_calls: ${msg.toolCalls.joinToString { it.name }}")
            }
        }
        val prompt = """
            Compact this conversation segment for a future coding-agent turn.
            Keep:
            - user goals and constraints
            - current implementation state
            - files, paths, commands, errors, and test results
            - tool calls and important outputs
            - unresolved next steps

            Return a dense structured summary under 1200 words.

            $transcript
        """.trimIndent()
        val out = StringBuilder()
        var failed = false
        provider.stream(
            ChatRequest(
                model = _ui.value.selectedModel,
                system = COMPACTION_SYSTEM_PROMPT,
                messages = listOf(ChatMessage(ChatMessage.Role.USER, prompt)),
                maxTokens = 1400,
            ),
        ).collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> out.append(event.text)
                is StreamEvent.Error -> failed = true
                else -> {}
            }
        }
        return out.toString().trim().takeIf { it.isNotBlank() && !failed }
            ?: fallbackCompactionSummary(previousSummary, messages)
    }

    private fun fallbackCompactionSummary(previousSummary: String?, messages: List<ChatMessage>): String = buildString {
        previousSummary?.takeIf { it.isNotBlank() }?.let {
            appendLine(it)
            appendLine()
        }
        appendLine("Recent compacted transcript:")
        messages.takeLast(12).forEach { msg ->
            appendLine("- ${msg.role.name.lowercase()}: ${msg.content.take(700).replace('\n', ' ')}")
        }
    }.take(6000)

    private fun compactedSummaryMessage(summary: String): String =
        "[Compacted context summary]\n$summary"

    private fun estimateTokens(messages: List<ChatMessage>): Int =
        messages.sumOf { (it.content.length / 4) + 12 + it.toolCalls.sumOf { call -> call.argumentsJson.length / 4 + 16 } }

    private fun StreamingMessage.toUiMessage(): UiMessage =
        UiMessage(messageId, MsgRole.ASSISTANT, MsgStatus.STREAMING, parts, System.currentTimeMillis())

    private fun pushLiveStats(chunks: Int, startedAt: Long) {
        val elapsedSec = (System.nanoTime() - startedAt) / 1_000_000_000.0
        _ui.value = _ui.value.copy(
            liveTokens = chunks,
            liveTokensPerSecond = if (elapsedSec > 0) chunks / elapsedSec else 0.0,
        )
    }
}
