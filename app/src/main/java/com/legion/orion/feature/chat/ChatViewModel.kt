package com.legion.orion.feature.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.agent.AppAgentTools
import com.legion.orion.claudecode.ClaudeCodeAuthManager
import com.legion.orion.claudecode.ClaudeCodeInstaller
import com.legion.orion.claudecode.ClaudeCodeProcess
import com.legion.orion.claudecode.ClaudeCodeToolchain
import com.legion.orion.claudecode.StreamJsonEventMapper
import com.legion.orion.core.database.ChatEntity
import com.legion.orion.core.database.ClaudeCodeSessionDao
import com.legion.orion.core.database.ClaudeCodeSessionEntity
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val SYSTEM_PROMPT = "You are Orion, a concise and helpful AI assistant."
private const val COMPACTION_SYSTEM_PROMPT =
    "You compact AI coding-agent conversations. Preserve concrete requirements, decisions, file paths, commands, errors, tool results, and unresolved tasks. Do not invent details."

/** Title shown for a chat that has not been named yet; chats keeping this title get auto-titled. */
private const val PLACEHOLDER_TITLE = "New chat"
private const val TITLE_SYSTEM_PROMPT =
    "You generate a short, specific title for a chat based on the user's request. " +
        "Reply with ONLY the title — 3 to 6 words, Title Case, no surrounding quotes, " +
        "no trailing punctuation, and no preamble or explanation."

/** Unified command modes: plain chat, agentic build (Orion's own tool loop), or real Claude Code CLI
 * (subscription-billed, runs in its own bundled Linux environment — see `com.legion.orion.claudecode`). */
enum class ChatMode { CHAT, BUILD, CLAUDE_CODE }

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
    val claudeCode: ClaudeCodeUiState = ClaudeCodeUiState(),
)

/** State for the Claude Code mode's provisioning/sign-in surface. */
data class ClaudeCodeUiState(
    val checking: Boolean = false,
    val installed: Boolean = false,
    val installProgress: String? = null,
    val loggedIn: Boolean = false,
    val email: String? = null,
    val subscriptionType: String? = null,
    /** Non-null while a login is in progress and waiting for the user to open it in a browser. */
    val loginUrl: String? = null,
    val loginBusy: Boolean = false,
    val loginError: String? = null,
    /** null = no `--model` flag, Claude Code uses the account's own default. */
    val model: String? = null,
    val effort: String = "medium",
    /** bypassPermissions, not acceptEdits — see SettingsRepository.claudeCodePermissionMode doc. */
    val permissionMode: String = "bypassPermissions",
    /** Real billing/usage numbers from the CLI's own `result` event (see [StreamJsonEventMapper.
     * TurnUsage]) — not an estimate. `sessionCostUsd` is Claude Code's own cumulative
     * `total_cost_usd` for the `--resume`d session as of the last completed turn. */
    val sessionCostUsd: Double = 0.0,
    val lastTurnInputTokens: Int = 0,
    val lastTurnOutputTokens: Int = 0,
    val lastTurnCacheReadTokens: Int = 0,
    val lastTurnCacheCreationTokens: Int = 0,
) {
    companion object {
        /** null entry renders as "Default" and omits --model entirely. */
        val MODEL_OPTIONS: List<String?> = listOf(null, "sonnet", "opus", "haiku", "fable")
        val EFFORT_OPTIONS = listOf("low", "medium", "high", "xhigh", "max")
        val PERMISSION_MODE_OPTIONS = listOf("acceptEdits", "auto", "bypassPermissions", "manual", "dontAsk", "plan")
    }
}

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
    @ApplicationContext private val appContext: Context,
    private val claudeCodeSessionDao: ClaudeCodeSessionDao,
    private val devServerState: com.legion.orion.runtime.DevServerState,
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

    // ── Claude Code (real CLI) ──
    private val claudeCodeToolchain by lazy { ClaudeCodeToolchain(appContext) }
    private val claudeCodeInstaller by lazy { ClaudeCodeInstaller(claudeCodeToolchain, appContext.assets) }
    private val claudeCodeAuth by lazy { ClaudeCodeAuthManager(claudeCodeToolchain) }
    private val claudeCodeProcesses = mutableMapOf<String, ClaudeCodeProcess>()
    private var claudeCodeLoginSession: ClaudeCodeAuthManager.LoginSession? = null

    val chats: StateFlow<List<ChatEntity>> = chatRepo.observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every project on device, for the project picker (continue an existing one, or start fresh). */
    val availableProjects: StateFlow<List<com.legion.orion.core.database.ProjectEntity>> = projects.observeProjects()
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
                _ui.value = _ui.value.copy(
                    activeChatId = recent.id,
                    mode = when (recent.mode) {
                        "build", "agent" -> ChatMode.BUILD
                        "claude_code" -> ChatMode.CLAUDE_CODE
                        else -> ChatMode.CHAT
                    },
                    projectName = recent.projectId?.let { projects.get(it)?.name },
                )
                rebuildConversationFrom(recent.id)
                localSessionChatId = recent.id
                localSessionPrimed = conversation.isEmpty()
                if (!localSessionPrimed) runCatching { controller.reset() }
                if (recent.mode == "claude_code") checkClaudeCodeReadiness()
            }
            backfillChatTitles()
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
        if (mode == ChatMode.CLAUDE_CODE) {
            // Deliberately does NOT auto-attach to an existing project here (unlike BUILD) — Claude
            // Code sessions are tied 1:1 to a project via --resume, so silently reusing "whichever
            // project happens to be first" on every mode switch would make a supposedly fresh chat
            // continue an unrelated old conversation. A project is created fresh on first send (see
            // [runClaudeCode]) unless the user explicitly continues one via the project picker.
            checkClaudeCodeReadiness()
            // Point Preview at the fixed Claude Code port right away (not just after a turn runs) so
            // switching to Preview before sending a message doesn't fall through to the unrelated
            // Node-based "start preview" flow, which Claude Code never touches.
            projectId?.let { devServerState.set(com.legion.orion.runtime.DevServerState.Running(ClaudeCodeProcess.PREVIEW_PORT, projects.dirFor(it).path)) }
        }
    }

    /** Refreshes install/auth status for the Claude Code sign-in surface. */
    fun checkClaudeCodeReadiness() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(claudeCode = _ui.value.claudeCode.copy(checking = true))
            val installed = claudeCodeToolchain.status().provisioned
            val auth = if (installed) runCatching { claudeCodeAuth.status() }.getOrNull() else null
            val model = settings.claudeCodeModel.first()
            val effort = settings.claudeCodeEffort.first()
            val permissionMode = settings.claudeCodePermissionMode.first()
            _ui.value = _ui.value.copy(
                claudeCode = _ui.value.claudeCode.copy(
                    checking = false,
                    installed = installed,
                    loggedIn = auth?.loggedIn == true,
                    email = auth?.email,
                    subscriptionType = auth?.subscriptionType,
                    model = model,
                    effort = effort,
                    permissionMode = permissionMode,
                ),
            )
        }
    }

    /** True once options change while a turn is in flight — the actual process teardown is deferred
     * to the turn's completion (see [runClaudeCode]) instead of stopping it out from under the
     * in-flight `sendTurn()` collection, which would abort the live response with a generic error. */
    private var claudeCodeOptionsDirty = false

    /** Updates model/effort/permission-mode, persists them, and restarts any live per-project Claude
     * Code process (with --resume) so the change takes effect on the next turn. */
    fun setClaudeCodeOptions(model: String?, effort: String, permissionMode: String) {
        _ui.value = _ui.value.copy(
            claudeCode = _ui.value.claudeCode.copy(model = model, effort = effort, permissionMode = permissionMode),
        )
        viewModelScope.launch {
            settings.setClaudeCodeOptions(model, effort, permissionMode)
            if (_ui.value.busy) {
                claudeCodeOptionsDirty = true
            } else {
                claudeCodeProcesses.values.forEach { it.stop() }
                claudeCodeProcesses.clear()
            }
        }
    }

    /** Installs the bundled proot + Ubuntu rootfs + Claude Code CLI runtime (one-time, ~150MB). */
    fun installClaudeCodeRuntime() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(claudeCode = _ui.value.claudeCode.copy(installProgress = "Starting install…"))
            runCatching {
                claudeCodeInstaller.install { progress ->
                    _ui.value = _ui.value.copy(claudeCode = _ui.value.claudeCode.copy(installProgress = progress))
                }
            }.onFailure {
                _ui.value = _ui.value.copy(claudeCode = _ui.value.claudeCode.copy(installProgress = null, loginError = "Install failed: ${it.message}"))
                return@launch
            }
            _ui.value = _ui.value.copy(claudeCode = _ui.value.claudeCode.copy(installProgress = null))
            checkClaudeCodeReadiness()
        }
    }

    /** Starts `claude auth login --claudeai`; the resulting URL is surfaced in `ui.claudeCode.loginUrl`
     * for the caller to open in a browser. The actual sign-in happens in the user's own browser. */
    fun beginClaudeCodeLogin() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(claudeCode = _ui.value.claudeCode.copy(loginBusy = true, loginError = null, loginUrl = null))
            runCatching {
                claudeCodeAuth.beginLogin { url ->
                    _ui.value = _ui.value.copy(claudeCode = _ui.value.claudeCode.copy(loginUrl = url))
                }
            }.onSuccess { session ->
                claudeCodeLoginSession = session
            }.onFailure {
                _ui.value = _ui.value.copy(claudeCode = _ui.value.claudeCode.copy(loginBusy = false, loginError = "Couldn't start sign-in: ${it.message}"))
            }
        }
    }

    /** Relays the code the user copied from the browser's redirect page into the waiting login process. */
    fun submitClaudeCodeLoginCode(code: String) {
        val session = claudeCodeLoginSession ?: return
        viewModelScope.launch {
            session.submitCode(code)
            val ok = session.awaitResult()
            claudeCodeLoginSession = null
            _ui.value = _ui.value.copy(
                claudeCode = _ui.value.claudeCode.copy(
                    loginBusy = false,
                    loginUrl = null,
                    loginError = if (ok) null else "Sign-in didn't complete — try again",
                ),
            )
            if (ok) checkClaudeCodeReadiness()
        }
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
        val text = state.input.trim()
        if (text.isEmpty() || state.busy) return

        if (state.mode == ChatMode.CLAUDE_CODE) {
            viewModelScope.launch {
                _ui.value = state.copy(input = "", busy = true, error = null, notice = null)
                val chatId = chatIdFlow.value ?: chatRepo.createChat(PLACEHOLDER_TITLE, "claude_code")
                    .also {
                        chatIdFlow.value = it.id
                        _ui.value = _ui.value.copy(activeChatId = it.id)
                    }.id
                chatRepo.addUserMessage(chatId, text)
                conversation.add(ChatMessage(ChatMessage.Role.USER, text))
                runClaudeCode(chatId, text)
            }
            return
        }

        val provider = state.selectedProvider ?: run {
            _ui.value = state.copy(error = "Select a provider first"); return
        }

        viewModelScope.launch {
            _ui.value = state.copy(input = "", busy = true, error = null, notice = null, liveTokens = 0, liveTokensPerSecond = 0.0)
            val chatId = chatIdFlow.value ?: chatRepo.createChat(PLACEHOLDER_TITLE, if (state.mode == ChatMode.BUILD) "build" else "ask")
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
                withContext(NonCancellable) { finalizeChat(chatId, assistantId, textBuf, thinkBuf, MsgStatus.CANCELLED, null, providerEntity, provider) }
                throw c
            } catch (t: Throwable) {
                errorMsg = t.message ?: "stream failed"
            }
            val status = if (errorMsg == null) MsgStatus.COMPLETE else MsgStatus.ERROR
            finalizeChat(chatId, assistantId, textBuf, thinkBuf, status, errorMsg, providerEntity, provider)
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
        providerEntity: ProviderEntity,
        provider: com.legion.orion.engine.providers.ChatProvider,
    ) {
        chatRepo.finishAssistantMessage(chatId, assistantId, textBuf.toString(), thinkBuf.toString(), status)
        if (textBuf.isNotEmpty()) conversation.add(ChatMessage(ChatMessage.Role.ASSISTANT, textBuf.toString()))
        streamingFlow.value = null
        if (status == MsgStatus.COMPLETE) withContext(NonCancellable) { maybeTitleChat(chatId, providerEntity, provider) }
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
            if (status == MsgStatus.COMPLETE) withContext(NonCancellable) { maybeTitleChat(chatId, providerEntity, provider) }
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

    // ── Claude Code (real CLI) ──

    /** Reuses (or lazily starts) the persistent per-project `proot`+`claude` process. */
    private suspend fun claudeCodeSessionFor(projectId: String, projectDir: File): ClaudeCodeProcess {
        claudeCodeProcesses[projectId]?.let { return it }
        val existing = claudeCodeSessionDao.get(projectId)
        val cc = _ui.value.claudeCode
        val process = ClaudeCodeProcess(
            toolchain = claudeCodeToolchain,
            projectDir = projectDir,
            claudeSessionId = existing?.claudeSessionId,
            onSessionIdAssigned = { sid ->
                viewModelScope.launch {
                    claudeCodeSessionDao.upsert(ClaudeCodeSessionEntity(projectId, sid, System.currentTimeMillis()))
                }
            },
            model = cc.model,
            effort = cc.effort,
            permissionMode = cc.permissionMode,
        )
        claudeCodeProcesses[projectId] = process
        return process
    }

    private suspend fun runClaudeCode(chatId: String, userText: String) {
        val isFreshProject = projectId == null
        if (isFreshProject) {
            projectResolutionMutex.withLock {
                // Blank directory — no Orion scaffold template copied in. Claude Code decides what
                // to create from scratch based on the actual request, rather than starting from
                // Orion's own static-web/vite/kotlin boilerplate (which was leaking into every
                // Claude Code project regardless of what was actually asked for).
                if (projectId == null) projectId = projects.createBlankProject("New Project").id
            }
        }
        val pid = projectId!!
        activeProject.set(pid)
        chatRepo.bindChatProject(chatId, pid, "claude_code")
        _ui.value = _ui.value.copy(projectName = projects.observeProjects().first().firstOrNull { it.id == pid }?.name)
        val dir = projects.dirFor(pid)

        if (!claudeCodeToolchain.status().provisioned) {
            _ui.value = _ui.value.copy(busy = false, error = "Claude Code isn't installed yet — open the mode picker to install it.")
            return
        }
        val auth = runCatching { claudeCodeAuth.status() }.getOrNull()
        if (auth?.loggedIn != true) {
            _ui.value = _ui.value.copy(busy = false, error = "Sign in to Claude Code first — open the mode picker to sign in.")
            return
        }

        // Claude Code is instructed (system prompt in ClaudeCodeProcess) to always serve a preview on
        // this fixed port in the background, so Preview just needs to point at it — no dependency on
        // Orion's own Node dev-server machinery, which Claude Code never touches.
        devServerState.set(com.legion.orion.runtime.DevServerState.Running(ClaudeCodeProcess.PREVIEW_PORT, dir.path))

        // Safety-net snapshot before handing control to Claude Code for this turn, exactly like BUILD
        // mode — Claude Code executes its own tools directly, so this is independent insurance, not a
        // guard that anything routes through.
        val snapshotter = GitSnapshotter(dir)
        val snapshotRef = runCatching { snapshotter.snapshot(pid, "claudecode_${System.currentTimeMillis()}") }.getOrNull()

        val process = claudeCodeSessionFor(pid, dir)
        val assistantId = chatRepo.beginAssistantMessage(chatId)
        val mapper = StreamJsonEventMapper()
        streamingFlow.value = StreamingMessage(assistantId, mapper.currentParts)

        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            var errorMsg: String? = null
            var resultText: String? = null
            try {
                process.sendTurn(userText).collect { obj ->
                    when (val outcome = mapper.accept(obj)) {
                        is StreamJsonEventMapper.Outcome.TurnComplete -> {
                            resultText = outcome.resultText
                            if (outcome.isError) errorMsg = outcome.resultText ?: "Claude Code reported an error"
                            outcome.usage?.let { u ->
                                _ui.value = _ui.value.copy(
                                    claudeCode = _ui.value.claudeCode.copy(
                                        sessionCostUsd = u.costUsd,
                                        lastTurnInputTokens = u.inputTokens,
                                        lastTurnOutputTokens = u.outputTokens,
                                        lastTurnCacheReadTokens = u.cacheReadTokens,
                                        lastTurnCacheCreationTokens = u.cacheCreationTokens,
                                    ),
                                )
                            }
                        }
                        is StreamJsonEventMapper.Outcome.ProcessDied -> errorMsg = outcome.message
                        StreamJsonEventMapper.Outcome.InProgress -> {}
                    }
                    val parts = if (snapshotRef != null) {
                        mapper.currentParts.map { p ->
                            if (p.type == PartType.TOOL_CALL && p.tool != null && p.tool.snapshotRef == null) {
                                p.copy(tool = p.tool.copy(snapshotRef = snapshotRef.ref, snapshotCommit = snapshotRef.commit))
                            } else p
                        }
                    } else {
                        mapper.currentParts
                    }
                    streamingFlow.value = StreamingMessage(assistantId, parts)
                }
            } catch (c: CancellationException) {
                withContext(NonCancellable) { chatRepo.finishAssistantParts(chatId, assistantId, mapper.currentParts, MsgStatus.CANCELLED) }
                streamingFlow.value = null
                _ui.value = _ui.value.copy(busy = false)
                throw c
            } catch (t: Throwable) {
                errorMsg = t.message ?: "Claude Code run failed"
            }
            val status = if (errorMsg == null) MsgStatus.COMPLETE else MsgStatus.ERROR
            chatRepo.finishAssistantParts(chatId, assistantId, mapper.currentParts, status)
            if (!resultText.isNullOrBlank()) conversation.add(ChatMessage(ChatMessage.Role.ASSISTANT, resultText!!))
            streamingFlow.value = null
            _ui.value = _ui.value.copy(busy = false, error = errorMsg)
            if (status == MsgStatus.COMPLETE) withContext(NonCancellable) { maybeNameClaudeCodeChatAndProject(chatId, pid, isFreshProject, userText) }
            if (claudeCodeOptionsDirty) {
                claudeCodeOptionsDirty = false
                claudeCodeProcesses.values.forEach { it.stop() }
                claudeCodeProcesses.clear()
            }
        }
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
            val chat = chatRepo.createChat(PLACEHOLDER_TITLE)
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
            mode = when (chat.mode) {
                "build", "agent" -> ChatMode.BUILD
                "claude_code" -> ChatMode.CLAUDE_CODE
                else -> ChatMode.CHAT
            },
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
        if (chat.mode == "claude_code") checkClaudeCodeReadiness()
    }

    /** Explicitly continues an existing project in the CURRENT chat (project picker "Continue" pick)
     * — this is the intentional counterpart to the old silent-reuse bug: the user is choosing to
     * resume this project's Claude Code session, not having it happen to them by accident. */
    fun selectProject(project: com.legion.orion.core.database.ProjectEntity) {
        projectId = project.id
        activeProject.set(project.id)
        _ui.value = _ui.value.copy(projectName = project.name)
        if (_ui.value.mode == ChatMode.CLAUDE_CODE) {
            devServerState.set(com.legion.orion.runtime.DevServerState.Running(ClaudeCodeProcess.PREVIEW_PORT, project.path))
        }
        viewModelScope.launch {
            chatIdFlow.value?.let { chatId ->
                val modeStr = when (_ui.value.mode) { ChatMode.BUILD -> "build"; ChatMode.CLAUDE_CODE -> "claude_code"; ChatMode.CHAT -> "ask" }
                chatRepo.bindChatProject(chatId, project.id, modeStr)
            }
        }
    }

    /** Explicitly detaches the current chat from any project (project picker "New Project" pick) —
     * the next Claude Code turn creates a genuinely fresh, blank project (see [runClaudeCode]). */
    fun startFreshProject() {
        projectId = null
        activeProject.set(null)
        _ui.value = _ui.value.copy(projectName = null)
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

    /** Serializes "resolve or create the fallback project" so a mode switch's [ensureProject] and a
     * concurrently-sent Claude Code turn's own fallback in [runClaudeCode] can't both observe
     * `projectId == null` and each create their own "Playground" project. */
    private val projectResolutionMutex = Mutex()

    private suspend fun ensureProject() {
        projectResolutionMutex.withLock {
            if (projectId != null) return@withLock
            val existing = projects.observeProjects().first().firstOrNull()
            val project = existing ?: projects.createProject("Playground")
            projectId = project.id
            activeProject.set(project.id)
            chatIdFlow.value?.let { chatRepo.bindChatProject(it, project.id, "build") }
            _ui.value = _ui.value.copy(projectName = project.name)
        }
    }

    // ── Chat titling ──

    /**
     * Claude Code has no [com.legion.orion.engine.providers.ChatProvider] to ask for a title (unlike
     * the cloud/local providers [maybeTitleChat] uses), so this names the chat — and, for a project
     * that was just created fresh for this exact turn, the project's display name too — from the
     * user's own request text via the same deterministic [fallbackTitle] the other modes fall back
     * to. Renaming the project only touches its Room display name, never its on-disk directory id.
     */
    private suspend fun maybeNameClaudeCodeChatAndProject(chatId: String, projectId: String, isFreshProject: Boolean, userText: String) {
        val chat = chatRepo.getChat(chatId) ?: return
        val title = fallbackTitle(userText)
        if (chat.title.trim() == PLACEHOLDER_TITLE) chatRepo.renameChat(chatId, title)
        if (isFreshProject) {
            projects.get(projectId)?.let { project ->
                if (project.name == "New Project") {
                    projects.rename(projectId, title)
                    _ui.value = _ui.value.copy(projectName = title)
                }
            }
        }
    }

    /**
     * Names a chat once its first exchange completes. Only acts on still-unnamed chats. Runs the
     * title request on the same provider, serialized within the finishing turn so it never races
     * the single MNN session; the local session is reset afterward so the next turn re-primes.
     */
    private suspend fun maybeTitleChat(
        chatId: String,
        providerEntity: ProviderEntity,
        provider: com.legion.orion.engine.providers.ChatProvider,
    ) {
        val chat = chatRepo.getChat(chatId) ?: return
        if (chat.title.trim() != PLACEHOLDER_TITLE) return
        val stored = chatRepo.observeMessages(chatId).first()
        val userText = firstText(stored, MsgRole.USER) ?: return
        val assistantText = firstText(stored, MsgRole.ASSISTANT)
        val title = generateTitle(provider, userText, assistantText) ?: fallbackTitle(userText)
        chatRepo.renameChat(chatId, title)
        if (providerEntity.type == ProviderType.MNN_LOCAL) {
            runCatching { controller.reset() }
            localSessionChatId = chatId
            localSessionPrimed = false
        }
    }

    /** One-time pass: give any leftover "New chat"/blank chats a name from their first message. */
    private suspend fun backfillChatTitles() {
        val chats = chatRepo.observeChats().first()
        chats.filter { it.title.isBlank() || it.title.trim() == PLACEHOLDER_TITLE }.forEach { chat ->
            val userText = firstText(chatRepo.observeMessages(chat.id).first(), MsgRole.USER) ?: return@forEach
            chatRepo.renameChat(chat.id, fallbackTitle(userText))
        }
    }

    private fun firstText(messages: List<UiMessage>, role: String): String? =
        messages.firstOrNull { it.role == role }
            ?.parts?.filter { it.type == PartType.TEXT }?.joinToString("\n") { it.text }
            ?.trim()?.takeIf { it.isNotEmpty() }

    /** Asks the model for a short title; returns null on any failure so the caller can fall back. */
    private suspend fun generateTitle(
        provider: com.legion.orion.engine.providers.ChatProvider,
        userText: String,
        assistantText: String?,
    ): String? {
        val transcript = buildString {
            append("User: ").append(userText.take(600))
            assistantText?.takeIf { it.isNotBlank() }?.let { append("\n\nAssistant: ").append(it.take(600)) }
        }
        val out = StringBuilder()
        var failed = false
        try {
            provider.stream(
                ChatRequest(
                    model = _ui.value.selectedModel,
                    system = TITLE_SYSTEM_PROMPT,
                    messages = listOf(ChatMessage(ChatMessage.Role.USER, "Title this conversation:\n\n$transcript")),
                    maxTokens = 24,
                    temperature = 0.3f,
                ),
            ).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> out.append(event.text)
                    is StreamEvent.Error -> failed = true
                    else -> {}
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            failed = true
        }
        return if (failed) null else cleanTitle(out.toString())
    }

    /** Normalizes raw model output into a tidy one-line title, or null if nothing usable remains. */
    private fun cleanTitle(raw: String): String? {
        var t = raw.substringAfterLast("</think>")
        t = t.replace(Regex("(?s)<think>.*?</think>"), " ")
        t = t.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        t = t.removePrefix("Title:").removePrefix("title:").trim()
        t = t.trim('"', '\'', '`', '*', '#', ' ', '-', '·')
        t = t.replace(Regex("\\s+"), " ").trimEnd('.', ',', ':', ';', '!', ' ')
        if (t.isBlank()) return null
        val words = t.split(' ')
        if (words.size > 8) t = words.take(8).joinToString(" ")
        if (t.length > 56) t = t.take(56).trim()
        return t.ifBlank { null }
    }

    /** Deterministic title from the first user message — used when the model can't produce one. */
    private fun fallbackTitle(userText: String): String {
        val firstLine = userText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return PLACEHOLDER_TITLE
        val collapsed = firstLine.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length > 40) collapsed.take(40).trim() + "…" else collapsed
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

    override fun onCleared() {
        super.onCleared()
        // Known limitation: without a dedicated foreground service, these die with the ViewModel /
        // app process; --resume (see ClaudeCodeProcess) reconnects the same conversation next time.
        claudeCodeProcesses.values.forEach { it.stop() }
        claudeCodeLoginSession?.cancel()
    }
}
