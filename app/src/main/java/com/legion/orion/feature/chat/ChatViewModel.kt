package com.legion.orion.feature.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.agent.AppAgentTools
import com.legion.orion.claudecode.ClaudeCodeAuthManager
import com.legion.orion.claudecode.ClaudeCodeInstaller
import com.legion.orion.claudecode.ClaudeCodeProcess
import com.legion.orion.claudecode.ClaudeCodeToolchain
import com.legion.orion.claudecode.ClaudeCodeUsageManager
import com.legion.orion.claudecode.ClaudeModelQuota
import com.legion.orion.claudecode.ClaudeToolBridge
import com.legion.orion.claudecode.ClaudePermissionDecision
import com.legion.orion.claudecode.StreamJsonEventMapper
import com.legion.orion.codex.CodexAuthManager
import com.legion.orion.codex.CodexEventMapper
import com.legion.orion.codex.CodexInstaller
import com.legion.orion.codex.CodexProcess
import com.legion.orion.codex.CodexToolchain
import com.legion.orion.grok.GrokAuthManager
import com.legion.orion.grok.GrokEventMapper
import com.legion.orion.grok.GrokInstaller
import com.legion.orion.grok.GrokProcess
import com.legion.orion.grok.GrokToolchain
import com.legion.orion.build.DevelopmentWorkService
import com.legion.orion.core.database.ChatEntity
import com.legion.orion.core.database.ClaudeCodeSessionDao
import com.legion.orion.core.database.ClaudeCodeSessionEntity
import com.legion.orion.core.database.CodexSessionEntity
import com.legion.orion.core.database.GrokSessionEntity
import com.legion.orion.core.database.ProviderEntity
import com.legion.orion.core.datastore.SettingsRepository
import com.legion.orion.data.chat.ChatRepository
import com.legion.orion.data.chat.ChatAttachment
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

/** How many trailing messages a cross-engine handoff brief carries. Enough to convey the current thread
 * of work without spending a large slice of the incoming engine's fresh context window — the code itself
 * is the real handoff, and both engines read the same working tree. */
private const val HANDOFF_MESSAGE_COUNT = 12

/** Per-message cap inside a handoff brief, so one giant pasted log can't crowd out every other message. */
private const val HANDOFF_MESSAGE_CHAR_LIMIT = 1500
private const val TITLE_SYSTEM_PROMPT =
    "You generate a short, specific title for a chat based on the user's request. " +
        "Reply with ONLY the title — 3 to 6 words, Title Case, no surrounding quotes, " +
        "no trailing punctuation, and no preamble or explanation."

/** Unified command modes: plain chat, agentic build (Orion's own tool loop), or one of the real
 * agent CLIs — Claude Code, Codex, and Grok Build — all subscription/account-billed and all running
 * in the same bundled Linux environment against the same projects.
 *
 * The three CLI modes are interchangeable per chat via [ChatViewModel.switchEngine] (usual reason:
 * one account hit its rate limit). */
enum class ChatMode { CHAT, BUILD, CLAUDE_CODE, CODEX, GROK }

/** Real-CLI modes [ChatViewModel.switchEngine] can move a conversation between. */
val ChatMode.isAgentCli: Boolean
    get() = this == ChatMode.CLAUDE_CODE || this == ChatMode.CODEX || this == ChatMode.GROK

data class StreamingMessage(
    val messageId: String,
    val parts: List<UiPart> = emptyList(),
)

data class ChatUiState(
    val providers: List<ProviderEntity> = emptyList(),
    val selectedProvider: ProviderEntity? = null,
    val selectedModel: String = "",
    val input: String = "",
    /** Files selected for the next Claude Code turn, copied into the current project sandbox. */
    val attachments: List<ChatAttachment> = emptyList(),
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
    val codex: CodexUiState = CodexUiState(),
    val grok: GrokUiState = GrokUiState(),
)

/**
 * State for Grok Build mode's provisioning/sign-in surface (counterpart of [CodexUiState]).
 * Device-code login: URL + short code, no paste-back field.
 *
 * Models / efforts / context windows are filled from Grok's live catalog (`/v1/models` +
 * `models_cache.json`). Context % comes from live stream usage and/or local `signals.json`.
 */
data class GrokUiState(
    val checking: Boolean = false,
    val installed: Boolean = false,
    val needsOnlyBinary: Boolean = false,
    val installProgress: String? = null,
    val installedVersion: String? = null,
    val loggedIn: Boolean = false,
    val authMethod: String? = null,
    val email: String? = null,
    val subscriptionTier: String? = null,
    val loginUrl: String? = null,
    val loginUserCode: String? = null,
    val loginBusy: Boolean = false,
    val loginError: String? = null,
    val accountActionBusy: Boolean = false,
    val accountActionError: String? = null,
    /** null = omit `-m` and let Grok pick its default model. */
    val model: String? = null,
    val effort: String = "high",
    val permissionMode: String = "bypassPermissions",
    val sessionCostUsd: Double = 0.0,
    val lastTurnInputTokens: Int = 0,
    val lastTurnOutputTokens: Int = 0,
    val lastTurnCacheReadTokens: Int = 0,
    val contextUsedTokens: Long = 0,
    val contextWindowTokens: Long = 500_000,
    val contextUsedPercentage: Double? = null,
    val contextRemainingPercentage: Double? = null,
    /** Billing-period usage from Grok `/v1/billing` (monthlyLimit / used). */
    val usageLimit: Long = 0,
    val usageUsed: Long = 0,
    val usagePercent: Double? = null,
    /** Live catalog from Grok Build (ids only; labels live in [availableModels]). */
    val availableModels: List<GrokModelUi> = listOf(GrokModelUi.DEFAULT),
    val effortOptions: List<GrokEffortUi> = GrokEffortUi.DEFAULTS,
    val accountUsageRefreshing: Boolean = false,
    val accountUsageError: String? = null,
    val accountUsageFetchedAt: Long? = null,
) {
    companion object {
        val EFFORT_OPTIONS = listOf("high", "medium", "low")
        val PERMISSION_OPTIONS = listOf("bypassPermissions", "acceptEdits", "default", "plan")
    }
}

data class GrokModelUi(
    val id: String,
    val name: String,
    val description: String = "",
    val contextWindow: Long = 500_000,
    val defaultEffort: String = "high",
    val efforts: List<GrokEffortUi> = GrokEffortUi.DEFAULTS,
) {
    companion object {
        val DEFAULT = GrokModelUi(
            id = "grok-4.5",
            name = "Grok 4.5",
            description = "xAI frontier model for Grok Build",
            contextWindow = 500_000,
            defaultEffort = "high",
        )
    }
}

data class GrokEffortUi(
    val id: String,
    val label: String,
    val description: String = "",
) {
    companion object {
        val DEFAULTS = listOf(
            GrokEffortUi("high", "High", "Highest implementation quality with extensive reasoning"),
            GrokEffortUi("medium", "Medium", "Balanced effort with standard implementation and testing"),
            GrokEffortUi("low", "Low", "Quick, fast implementations"),
        )
    }
}

/**
 * State for the Codex mode's provisioning/sign-in surface, the counterpart of [ClaudeCodeUiState].
 *
 * Intentionally NOT merged with [ClaudeCodeUiState] behind one "agent CLI" state class: the two engines
 * genuinely differ in what they can report (Claude has dollar cost and 5h/7d quota windows; Codex has
 * token counts and a device-code login), and collapsing them would mean a pile of fields that are null
 * for one engine and meaningful for the other.
 */
data class CodexUiState(
    val checking: Boolean = false,
    val installed: Boolean = false,
    /** True when the shared Linux runtime is present and only Codex's own binary is missing — the UI
     * offers a ~105MB download rather than a full runtime install. */
    val needsOnlyBinary: Boolean = false,
    val installProgress: String? = null,
    val installedVersion: String? = null,
    val loggedIn: Boolean = false,
    /** e.g. "ChatGPT subscription" — Codex reports the auth mode rather than an account email. */
    val authMethod: String? = null,
    /** Non-null while a device-code login is waiting for the user to approve it in a browser. */
    val loginUrl: String? = null,
    /** The short code the user types on the verification page; some flows embed it in the URL instead. */
    val loginUserCode: String? = null,
    val loginBusy: Boolean = false,
    val loginError: String? = null,
    val accountActionBusy: Boolean = false,
    val accountActionError: String? = null,
    /** null = no `-m` flag, Codex uses its own default model. */
    val model: String? = null,
    val effort: String = "medium",
    /** danger-full-access — see SettingsRepository.codexSandbox for why that is right under proot. */
    val sandbox: String = "danger-full-access",
    val lastTurnInputTokens: Int = 0,
    val lastTurnOutputTokens: Int = 0,
    val lastTurnCacheReadTokens: Int = 0,
    val contextUsedTokens: Long = 0,
    val contextWindowTokens: Long = 0,
) {
    companion object {
        /** null entry renders as "Default" and omits `-m` entirely. */
        val MODEL_OPTIONS: List<String?> = listOf(null, "gpt-5-codex", "gpt-5", "o4-mini")

        /** Same vocabulary as Claude's effort control so the two option sheets stay symmetrical; passed
         * through as `-c model_reasoning_effort`. Codex has no "max" tier. */
        val EFFORT_OPTIONS = listOf("low", "medium", "high", "xhigh")

        val SANDBOX_OPTIONS = listOf("danger-full-access", "workspace-write", "read-only")
    }
}

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
    /** True while a logout or account switch is safely closing running Claude work. */
    val accountActionBusy: Boolean = false,
    val accountActionError: String? = null,
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
    /** Live values supplied by Claude Code's official status-line JSON contract. */
    val contextUsedTokens: Long = 0,
    val contextWindowTokens: Long = 0,
    val contextUsedPercentage: Double? = null,
    val contextRemainingPercentage: Double? = null,
    val fiveHourUsedPercentage: Double? = null,
    val fiveHourResetsAt: Long? = null,
    val sevenDayUsedPercentage: Double? = null,
    val sevenDayResetsAt: Long? = null,
    val modelScopedQuotas: List<ClaudeModelQuota> = listOf(ClaudeModelQuota("Fable")),
    val accountUsageRefreshing: Boolean = false,
    val accountUsageError: String? = null,
    val sessionDurationMs: Long? = null,
    val telemetryUpdatedAt: Long? = null,
) {
    companion object {
        /** null entry renders as "Default" and omits --model entirely. */
        val MODEL_OPTIONS: List<String?> = listOf(null, "sonnet", "opus", "haiku", "fable")
        val EFFORT_OPTIONS = listOf("low", "medium", "high", "xhigh", "max")
        val PERMISSION_MODE_OPTIONS = listOf("default", "acceptEdits", "auto", "bypassPermissions", "dontAsk", "plan")
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
    private val godotAgentTools: com.legion.orion.agent.GodotAgentTools,
    private val controller: InferenceController,
    @ApplicationContext private val appContext: Context,
    private val claudeCodeSessionDao: ClaudeCodeSessionDao,
    private val codexSessionDao: com.legion.orion.core.database.CodexSessionDao,
    private val grokSessionDao: com.legion.orion.core.database.GrokSessionDao,
    private val devServerState: com.legion.orion.runtime.DevServerState,
    private val claudeToolBridge: ClaudeToolBridge,
    private val runtimeBootstrap: com.legion.orion.runtime.RuntimeBootstrap,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState())
    val ui = _ui.asStateFlow()

    private val chatIdFlow = MutableStateFlow<String?>(null)
    private val streamingFlow = MutableStateFlow<StreamingMessage?>(null)

    private val conversation = mutableListOf<ChatMessage>()
    private var streamJob: Job? = null
    private var localSessionChatId: String? = null
    private var localSessionPrimed = false

    private val registry by lazy { ToolRegistry(DefaultTools.list() + appAgentTools.list() + godotAgentTools.list()) }
    private var projectId: String? = null

    // ── Claude Code (real CLI) ──
    private val claudeCodeToolchain by lazy { ClaudeCodeToolchain(appContext) }
    private val claudeCodeInstaller by lazy { ClaudeCodeInstaller(claudeCodeToolchain, appContext.assets) }
    private val claudeCodeAuth by lazy { ClaudeCodeAuthManager(claudeCodeToolchain) }
    private val claudeCodeUsage by lazy { ClaudeCodeUsageManager(claudeCodeToolchain) }
    private val claudeCodeProcesses = mutableMapOf<String, ClaudeCodeProcess>()
    private var claudeCodeLoginSession: ClaudeCodeAuthManager.LoginSession? = null
    private val claudeUsageRefreshMutex = Mutex()
    private var lastClaudeUsageRefreshAt = 0L
    private var lastClaudeUsageFailureAt = 0L

    // ── Codex (real CLI) ──
    // Shares the Claude toolchain's proot + Ubuntu rootfs rather than provisioning a second one, which
    // is also what makes both engines see the same projects/git/terminal (see CodexToolchain).
    private val codexToolchain by lazy { CodexToolchain(claudeCodeToolchain) }
    private val codexInstaller by lazy { CodexInstaller(codexToolchain) }
    private val codexAuth by lazy { CodexAuthManager(codexToolchain) }
    private val codexProcesses = mutableMapOf<String, CodexProcess>()
    private var codexLoginSession: CodexAuthManager.LoginSession? = null

    // ── Grok Build (real CLI) ──
    private val grokToolchain by lazy { GrokToolchain(claudeCodeToolchain) }
    private val grokInstaller by lazy { GrokInstaller(grokToolchain) }
    private val grokAuth by lazy { GrokAuthManager(grokToolchain) }
    private val grokAccount by lazy { com.legion.orion.grok.GrokAccountService(grokToolchain) }
    private val grokProcesses = mutableMapOf<String, GrokProcess>()
    private var grokLoginSession: GrokAuthManager.LoginSession? = null
    private var lastGrokUsageRefreshAt = 0L

    val chats: StateFlow<List<ChatEntity>> = chatRepo.observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every project on device, for the project picker (continue an existing one, or start fresh). */
    val availableProjects: StateFlow<List<com.legion.orion.core.database.ProjectEntity>> = projects.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val engineState: StateFlow<EngineState> = controller.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), controller.state.value)

    val pendingClaudePermissions = claudeToolBridge.pendingPermissions

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
                    mode = chatModeOf(recent.mode),
                    projectName = recent.projectId?.let { projects.get(it)?.name },
                )
                rebuildConversationFrom(recent.id)
                localSessionChatId = recent.id
                localSessionPrimed = conversation.isEmpty()
                if (!localSessionPrimed) runCatching { controller.reset() }
                if (recent.mode == "claude_code") checkClaudeCodeReadiness()
                if (recent.mode == "codex") checkCodexReadiness()
                if (recent.mode == "grok") checkGrokReadiness()
            }
            backfillChatTitles()
            // Always refresh CLI readiness after cold start — bootstrap may already be installing.
            checkClaudeCodeReadiness()
            checkCodexReadiness()
            checkGrokReadiness()
        }

        // Mirror automatic RuntimeBootstrap progress into the mode banners (no Install tap needed).
        viewModelScope.launch {
            runtimeBootstrap.state.collect { boot ->
                if (boot.busy) {
                    val line = listOfNotNull(boot.message, boot.detail.takeIf { it.isNotBlank() })
                        .joinToString(" — ")
                    _ui.value = _ui.value.copy(
                        claudeCode = _ui.value.claudeCode.copy(
                            installProgress = if (!boot.claudeReady) line else null,
                            installed = boot.claudeReady || _ui.value.claudeCode.installed,
                        ),
                        codex = _ui.value.codex.copy(
                            installProgress = if (!boot.codexReady) line else null,
                            installed = boot.codexReady || _ui.value.codex.installed,
                        ),
                        grok = _ui.value.grok.copy(
                            installProgress = if (!boot.grokReady) line else null,
                            installed = boot.grokReady || _ui.value.grok.installed,
                        ),
                    )
                }
                if (boot.finishedPass) {
                    _ui.value = _ui.value.copy(
                        claudeCode = _ui.value.claudeCode.copy(installProgress = null),
                        codex = _ui.value.codex.copy(installProgress = null),
                        grok = _ui.value.grok.copy(installProgress = null),
                    )
                    checkClaudeCodeReadiness()
                    checkCodexReadiness()
                    checkGrokReadiness()
                }
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

        viewModelScope.launch {
            combine(activeProject.projectId, claudeToolBridge.sessionTelemetry) { activeId, telemetry ->
                activeId?.let { id ->
                    val path = runCatching { projects.dirFor(id).canonicalPath }.getOrNull()
                    path?.let(telemetry::get)
                }
            }.collect { live ->
                val current = _ui.value.claudeCode
                _ui.value = _ui.value.copy(
                    claudeCode = current.copy(
                        sessionCostUsd = live?.costUsd ?: current.sessionCostUsd,
                        contextUsedTokens = live?.contextUsedTokens ?: 0,
                        contextWindowTokens = live?.contextWindowTokens ?: 0,
                        contextUsedPercentage = live?.contextUsedPercentage,
                        contextRemainingPercentage = live?.contextRemainingPercentage,
                        fiveHourUsedPercentage = live?.fiveHour?.usedPercentage ?: current.fiveHourUsedPercentage,
                        fiveHourResetsAt = live?.fiveHour?.resetsAtEpochSeconds ?: current.fiveHourResetsAt,
                        sevenDayUsedPercentage = live?.sevenDay?.usedPercentage ?: current.sevenDayUsedPercentage,
                        sevenDayResetsAt = live?.sevenDay?.resetsAtEpochSeconds ?: current.sevenDayResetsAt,
                        sessionDurationMs = live?.durationMs,
                        telemetryUpdatedAt = live?.receivedAt,
                    ),
                )
            }
        }

        // Account quotas / catalogs are global rather than project-specific. Refresh once per minute
        // while an agent CLI surface is active.
        viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                when {
                    _ui.value.mode == ChatMode.CLAUDE_CODE && _ui.value.claudeCode.loggedIn ->
                        refreshClaudeAccountUsageInternal(force = false)
                    _ui.value.mode == ChatMode.GROK && _ui.value.grok.loggedIn ->
                        refreshGrokAccountUsageInternal(force = false)
                }
            }
        }
    }

    fun onInput(text: String) { _ui.value = _ui.value.copy(input = text) }

    /** Copies selected documents into the project before the agent sees them; source URIs are never
     * retained. Serves both CLI engines — the files land in the shared project sandbox either way. */
    fun addClaudeCodeAttachments(uris: List<Uri>) {
        if (uris.isEmpty() || !_ui.value.mode.isAgentCli) return
        viewModelScope.launch {
            runCatching {
                val pid = projectId ?: projectResolutionMutex.withLock {
                    projectId ?: projects.createBlankProject("New Project").id.also { projectId = it }
                }
                activeProject.set(pid)
                val projectDir = projects.dirFor(pid)
                val attachmentDir = File(projectDir, ".orion/attachments").apply { mkdirs() }
                uris.map { uri ->
                    val displayName = uri.lastPathSegment
                        ?.substringAfterLast('/')
                        ?.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                        ?.take(96)
                        ?.ifBlank { "attachment" }
                        ?: "attachment"
                    val storedName = "${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}_$displayName"
                    val target = File(attachmentDir, storedName)
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    } ?: error("Couldn't read $displayName")
                    ChatAttachment(
                        displayName = displayName,
                        localPath = target.absolutePath,
                        claudePath = "/root/project/.orion/attachments/$storedName",
                        mimeType = appContext.contentResolver.getType(uri),
                        sizeBytes = target.length(),
                    )
                }
            }.onSuccess { added ->
                _ui.value = _ui.value.copy(attachments = _ui.value.attachments + added, error = null)
            }.onFailure { error ->
                _ui.value = _ui.value.copy(error = "Couldn't attach file: ${error.message ?: "Unknown error"}")
            }
        }
    }

    fun removeClaudeCodeAttachment(attachment: ChatAttachment) {
        _ui.value = _ui.value.copy(attachments = _ui.value.attachments - attachment)
    }

    fun respondToClaudePermission(id: String, decision: ClaudePermissionDecision) {
        claudeToolBridge.respondToPermission(id, decision)
    }

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
        if (mode == ChatMode.CODEX) {
            // Same reasoning as the Claude branch above, for the same reason: a Codex chat's continuity
            // is a stored thread id tied to one project, so auto-attaching "whichever project is first"
            // would silently resume an unrelated conversation. Project creation happens in [runCodex].
            checkCodexReadiness()
            projectId?.let { devServerState.set(com.legion.orion.runtime.DevServerState.Running(ClaudeCodeProcess.PREVIEW_PORT, projects.dirFor(it).path)) }
        }
        if (mode == ChatMode.GROK) {
            checkGrokReadiness()
            projectId?.let { devServerState.set(com.legion.orion.runtime.DevServerState.Running(ClaudeCodeProcess.PREVIEW_PORT, projects.dirFor(it).path)) }
        }
    }

    /** The chat-mode string persisted on `ChatEntity.mode` ⇄ [ChatMode]. Kept in one place because it is
     * read on cold start, on chat open, and on project bind, and a mode missing from one of those
     * branches silently degrades a chat to plain CHAT. */
    private fun chatModeOf(stored: String?): ChatMode = when (stored) {
        "build", "agent" -> ChatMode.BUILD
        "claude_code" -> ChatMode.CLAUDE_CODE
        "codex" -> ChatMode.CODEX
        "grok" -> ChatMode.GROK
        else -> ChatMode.CHAT
    }

    private fun modeString(mode: ChatMode): String = when (mode) {
        ChatMode.BUILD -> "build"
        ChatMode.CLAUDE_CODE -> "claude_code"
        ChatMode.CODEX -> "codex"
        ChatMode.GROK -> "grok"
        ChatMode.CHAT -> "ask"
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
            if (auth?.loggedIn == true) refreshClaudeAccountUsageInternal(force = false)
        }
    }

    fun refreshClaudeAccountUsage() {
        viewModelScope.launch { refreshClaudeAccountUsageInternal(force = true) }
    }

    // ── Codex provisioning / sign-in ──

    /** Refreshes install/auth status for the Codex sign-in surface. */
    fun checkCodexReadiness() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(codex = _ui.value.codex.copy(checking = true))
            val status = codexToolchain.status()
            val auth = if (status.provisioned) runCatching { codexAuth.status() }.getOrNull() else null
            _ui.value = _ui.value.copy(
                codex = _ui.value.codex.copy(
                    checking = false,
                    installed = status.provisioned,
                    needsOnlyBinary = status.needsOnlyCodexBinary,
                    installedVersion = status.installedVersion,
                    loggedIn = auth?.loggedIn == true,
                    authMethod = auth?.method,
                    model = settings.codexModel.first(),
                    effort = settings.codexEffort.first(),
                    sandbox = settings.codexSandbox.first(),
                ),
            )
        }
    }

    /** Downloads the Codex CLI into the shared rootfs (~105MB, expands to ~257MB). */
    fun installCodexRuntime(force: Boolean = false) {
        if (_ui.value.codex.installProgress != null) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(codex = _ui.value.codex.copy(installProgress = "Starting download…", loginError = null))
            runCatching {
                codexInstaller.install(force = force) { progress ->
                    _ui.value = _ui.value.copy(codex = _ui.value.codex.copy(installProgress = progress))
                }
            }.onFailure {
                _ui.value = _ui.value.copy(
                    codex = _ui.value.codex.copy(
                        installProgress = null,
                        loginError = "Codex install failed: ${it.message}",
                    ),
                )
                return@launch
            }
            _ui.value = _ui.value.copy(codex = _ui.value.codex.copy(installProgress = null))
            checkCodexReadiness()
        }
    }

    /**
     * Starts `codex login --device-auth`. The verification URL and code land in `ui.codex.loginUrl` /
     * `loginUserCode` for the user to open in any browser — including on another device, which is the
     * main reason this flow was chosen over the redirect-based one.
     */
    fun beginCodexLogin() {
        if (_ui.value.codex.loginBusy || _ui.value.codex.accountActionBusy) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                codex = _ui.value.codex.copy(
                    loginBusy = true,
                    loginError = null,
                    loginUrl = null,
                    loginUserCode = null,
                ),
            )
            val session = runCatching {
                codexAuth.beginDeviceLogin { prompt ->
                    _ui.value = _ui.value.copy(
                        codex = _ui.value.codex.copy(loginUrl = prompt.url, loginUserCode = prompt.userCode),
                    )
                }
            }.getOrElse { failure ->
                _ui.value = _ui.value.copy(
                    codex = _ui.value.codex.copy(
                        loginBusy = false,
                        loginError = failure.message ?: "Could not start Codex sign-in",
                    ),
                )
                return@launch
            }
            codexLoginSession = session

            // The CLI polls for approval itself, so this just waits for its exit. Keeping the await here
            // (rather than requiring a second UI action, as the Claude paste-a-code flow does) is what
            // makes the device-code flow one-tap from the user's side.
            val ok = session.awaitResult()
            codexLoginSession = null
            _ui.value = _ui.value.copy(
                codex = _ui.value.codex.copy(
                    loginBusy = false,
                    loginUrl = null,
                    loginUserCode = null,
                    loginError = if (ok) null else {
                        session.output().lineSequence().lastOrNull { it.isNotBlank() }?.take(240)
                            ?: "Codex sign-in did not complete"
                    },
                ),
            )
            if (ok) checkCodexReadiness()
        }
    }

    fun cancelCodexLogin() {
        codexLoginSession?.cancel()
        codexLoginSession = null
        _ui.value = _ui.value.copy(
            codex = _ui.value.codex.copy(loginBusy = false, loginUrl = null, loginUserCode = null),
        )
    }

    fun signOutCodex() {
        if (_ui.value.codex.accountActionBusy || _ui.value.codex.loginBusy) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                codex = _ui.value.codex.copy(accountActionBusy = true, accountActionError = null),
            )
            runCatching {
                // Stop live work and drop every stored thread id BEFORE signing out: a thread belongs to
                // the account that created it, so resuming one under a different account must never be
                // attempted. Same ordering as the Claude sign-out path.
                codexProcesses.values.forEach { it.stop() }
                codexProcesses.clear()
                codexOptionsDirty = false
                codexSessionDao.clearAll()
                codexAuth.logout()
            }.onFailure { failure ->
                _ui.value = _ui.value.copy(
                    codex = _ui.value.codex.copy(
                        accountActionBusy = false,
                        accountActionError = failure.message?.take(240) ?: "Codex sign-out failed",
                    ),
                )
                return@launch
            }
            _ui.value = _ui.value.copy(
                codex = _ui.value.codex.copy(
                    accountActionBusy = false,
                    loggedIn = false,
                    authMethod = null,
                    lastTurnInputTokens = 0,
                    lastTurnOutputTokens = 0,
                    lastTurnCacheReadTokens = 0,
                    contextUsedTokens = 0,
                ),
            )
            checkCodexReadiness()
        }
    }

    /** Codex counterpart of [claudeCodeOptionsDirty]; see that field for why teardown is deferred. */
    private var codexOptionsDirty = false

    fun setCodexOptions(model: String?, effort: String, sandbox: String) {
        _ui.value = _ui.value.copy(
            codex = _ui.value.codex.copy(model = model, effort = effort, sandbox = sandbox),
        )
        viewModelScope.launch {
            settings.setCodexOptions(model, effort, sandbox)
            if (_ui.value.busy) {
                codexOptionsDirty = true
            } else {
                codexProcesses.values.forEach { it.stop() }
                codexProcesses.clear()
            }
        }
    }

    // ── Grok Build provisioning / sign-in ──

    fun checkGrokReadiness() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(grok = _ui.value.grok.copy(checking = true))
            val status = grokToolchain.status()
            val auth = if (status.provisioned) runCatching { grokAuth.status() }.getOrNull() else null
            var model = settings.grokModel.first()
            // Migrate obsolete model ids (old UI offered a fake "grok-build" chip).
            if (model.equals("grok-build", ignoreCase = true)) model = "grok-4.5"
            val effort = settings.grokEffort.first().let { e ->
                when (e) {
                    "xhigh", "max", "none", "minimal" -> "high"
                    else -> e
                }
            }
            val permissionMode = settings.grokPermissionMode.first()
            // Seed catalog + context from disk immediately (fast), then refresh live catalog.
            val localSnap = if (status.provisioned && auth?.loggedIn == true) {
                runCatching { grokAccount.fetch(includeNetwork = false) }.getOrNull()
            } else {
                null
            }
            val catalog = localSnap?.models?.map { it.toUi() }?.ifEmpty { null }
                ?: listOf(GrokModelUi.DEFAULT)
            // Prefer a real catalog model — never leave a null/"Grok" placeholder when we know the only model is Grok 4.5.
            val resolvedModel = when {
                model != null && catalog.any { it.id == model } -> model
                localSnap?.defaultModelId != null && catalog.any { it.id == localSnap.defaultModelId } ->
                    localSnap.defaultModelId
                else -> catalog.firstOrNull()?.id ?: "grok-4.5"
            }
            if (resolvedModel != model) {
                settings.setGrokOptions(resolvedModel, effort, permissionMode)
            }
            val selectedModelMeta = catalog.firstOrNull { it.id == resolvedModel } ?: catalog.firstOrNull()
            val efforts = (selectedModelMeta?.efforts ?: GrokEffortUi.DEFAULTS)
                .filter { it.id in setOf("high", "medium", "low") }
                .ifEmpty { GrokEffortUi.DEFAULTS }
            val window = when {
                localSnap != null && localSnap.contextWindowTokens > 0 -> localSnap.contextWindowTokens
                selectedModelMeta != null -> selectedModelMeta.contextWindow
                else -> 500_000L
            }
            val used = localSnap?.contextTokensUsed ?: 0L
            val pct = localSnap?.contextWindowUsagePercent
                ?: if (window > 0 && used > 0) used * 100.0 / window else null
            _ui.value = _ui.value.copy(
                grok = _ui.value.grok.copy(
                    checking = false,
                    installed = status.provisioned,
                    needsOnlyBinary = status.needsOnlyGrokBinary,
                    installedVersion = status.installedVersion,
                    loggedIn = auth?.loggedIn == true,
                    authMethod = auth?.method ?: localSnap?.email,
                    email = localSnap?.email,
                    subscriptionTier = localSnap?.subscriptionTier,
                    model = resolvedModel,
                    effort = if (effort in efforts.map { it.id }) effort else (selectedModelMeta?.defaultEffort ?: "high"),
                    permissionMode = permissionMode,
                    availableModels = catalog,
                    effortOptions = efforts,
                    contextUsedTokens = used,
                    contextWindowTokens = window,
                    contextUsedPercentage = pct,
                    contextRemainingPercentage = pct?.let { (100.0 - it).coerceAtLeast(0.0) },
                ),
            )
            if (auth?.loggedIn == true) refreshGrokAccountUsage(force = false)
        }
    }

    fun refreshGrokAccountUsage(force: Boolean = true) {
        viewModelScope.launch { refreshGrokAccountUsageInternal(force) }
    }

    private suspend fun refreshGrokAccountUsageInternal(force: Boolean) {
        if (!_ui.value.grok.installed || !_ui.value.grok.loggedIn) return
        val now = System.currentTimeMillis()
        if (!force && now - lastGrokUsageRefreshAt < 45_000L) return
        if (_ui.value.grok.accountUsageRefreshing) return
        _ui.value = _ui.value.copy(
            grok = _ui.value.grok.copy(accountUsageRefreshing = true, accountUsageError = null),
        )
        val result = runCatching { grokAccount.fetch(includeNetwork = true) }
        lastGrokUsageRefreshAt = System.currentTimeMillis()
        result.onSuccess { snap ->
            val catalog = snap.models.map { it.toUi() }.ifEmpty { listOf(GrokModelUi.DEFAULT) }
            var selectedId = _ui.value.grok.model
            if (selectedId == null || catalog.none { it.id == selectedId }) {
                selectedId = snap.defaultModelId ?: catalog.first().id
                settings.setGrokOptions(selectedId, _ui.value.grok.effort, _ui.value.grok.permissionMode)
            }
            val selected = catalog.firstOrNull { it.id == selectedId } ?: catalog.firstOrNull()
            val efforts = (selected?.efforts ?: GrokEffortUi.DEFAULTS)
                .filter { it.id in setOf("high", "medium", "low") }
                .ifEmpty { GrokEffortUi.DEFAULTS }
            val window = when {
                snap.contextWindowTokens > 0 -> snap.contextWindowTokens
                selected != null -> selected.contextWindow
                else -> _ui.value.grok.contextWindowTokens
            }
            // Prefer session signals for context fill — not last-turn token totals.
            val used = if (snap.contextTokensUsed > 0) snap.contextTokensUsed else _ui.value.grok.contextUsedTokens
            val pct = snap.contextWindowUsagePercent
                ?: if (window > 0 && used > 0) used * 100.0 / window else _ui.value.grok.contextUsedPercentage
            val effort = _ui.value.grok.effort.let { e ->
                if (e in efforts.map { it.id }) e else (selected?.defaultEffort ?: "high")
            }
            _ui.value = _ui.value.copy(
                grok = _ui.value.grok.copy(
                    accountUsageRefreshing = false,
                    accountUsageError = snap.error,
                    accountUsageFetchedAt = snap.fetchedAtMillis,
                    email = snap.email ?: _ui.value.grok.email,
                    authMethod = snap.email ?: snap.displayName ?: _ui.value.grok.authMethod,
                    subscriptionTier = snap.subscriptionTier ?: _ui.value.grok.subscriptionTier,
                    availableModels = catalog,
                    model = selectedId,
                    effortOptions = efforts,
                    effort = effort,
                    contextUsedTokens = used,
                    contextWindowTokens = window,
                    contextUsedPercentage = pct,
                    contextRemainingPercentage = pct?.let { (100.0 - it).coerceAtLeast(0.0) },
                    usageLimit = snap.usageLimit,
                    usageUsed = snap.usageUsed,
                    usagePercent = snap.usagePercent,
                ),
            )
        }.onFailure {
            _ui.value = _ui.value.copy(
                grok = _ui.value.grok.copy(
                    accountUsageRefreshing = false,
                    accountUsageError = it.message?.take(200) ?: "Could not refresh Grok account",
                ),
            )
        }
    }

    private fun com.legion.orion.grok.GrokAccountService.ModelOption.toUi() = GrokModelUi(
        id = id,
        name = name,
        description = description,
        contextWindow = contextWindow,
        defaultEffort = defaultEffort,
        efforts = efforts.map { GrokEffortUi(it.id, it.label, it.description) },
    )

    fun installGrokRuntime(force: Boolean = false) {
        if (_ui.value.grok.installProgress != null) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(grok = _ui.value.grok.copy(installProgress = "Starting download…", loginError = null))
            runCatching {
                grokInstaller.install(force = force) { progress ->
                    _ui.value = _ui.value.copy(grok = _ui.value.grok.copy(installProgress = progress))
                }
            }.onFailure {
                _ui.value = _ui.value.copy(
                    grok = _ui.value.grok.copy(
                        installProgress = null,
                        loginError = "Grok Build install failed: ${it.message}",
                    ),
                )
                return@launch
            }
            _ui.value = _ui.value.copy(grok = _ui.value.grok.copy(installProgress = null))
            checkGrokReadiness()
        }
    }

    fun beginGrokLogin() {
        if (_ui.value.grok.loginBusy || _ui.value.grok.accountActionBusy) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                grok = _ui.value.grok.copy(
                    loginBusy = true,
                    loginError = null,
                    loginUrl = null,
                    loginUserCode = null,
                ),
            )
            val session = runCatching {
                grokAuth.beginDeviceLogin { prompt ->
                    _ui.value = _ui.value.copy(
                        grok = _ui.value.grok.copy(loginUrl = prompt.url, loginUserCode = prompt.userCode),
                    )
                }
            }.getOrElse { failure ->
                _ui.value = _ui.value.copy(
                    grok = _ui.value.grok.copy(
                        loginBusy = false,
                        loginError = failure.message ?: "Could not start Grok Build sign-in",
                    ),
                )
                return@launch
            }
            grokLoginSession = session
            val ok = session.awaitResult()
            grokLoginSession = null
            _ui.value = _ui.value.copy(
                grok = _ui.value.grok.copy(
                    loginBusy = false,
                    loginUrl = null,
                    loginUserCode = null,
                    loginError = if (ok) null else {
                        session.output().lineSequence().lastOrNull { it.isNotBlank() }?.take(240)
                            ?: "Grok Build sign-in did not complete"
                    },
                ),
            )
            if (ok) checkGrokReadiness()
        }
    }

    fun cancelGrokLogin() {
        grokLoginSession?.cancel()
        grokLoginSession = null
        _ui.value = _ui.value.copy(
            grok = _ui.value.grok.copy(loginBusy = false, loginUrl = null, loginUserCode = null),
        )
    }

    fun signOutGrok() {
        if (_ui.value.grok.accountActionBusy || _ui.value.grok.loginBusy) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                grok = _ui.value.grok.copy(accountActionBusy = true, accountActionError = null),
            )
            runCatching {
                grokProcesses.values.forEach { it.stop() }
                grokProcesses.clear()
                grokOptionsDirty = false
                grokSessionDao.clearAll()
                grokAuth.logout()
            }.onFailure { failure ->
                _ui.value = _ui.value.copy(
                    grok = _ui.value.grok.copy(
                        accountActionBusy = false,
                        accountActionError = failure.message?.take(240) ?: "Grok Build sign-out failed",
                    ),
                )
                return@launch
            }
            _ui.value = _ui.value.copy(
                grok = _ui.value.grok.copy(
                    accountActionBusy = false,
                    loggedIn = false,
                    authMethod = null,
                    sessionCostUsd = 0.0,
                    lastTurnInputTokens = 0,
                    lastTurnOutputTokens = 0,
                    lastTurnCacheReadTokens = 0,
                    contextUsedTokens = 0,
                ),
            )
            checkGrokReadiness()
        }
    }

    private var grokOptionsDirty = false

    fun setGrokOptions(model: String?, effort: String, permissionMode: String) {
        val catalog = _ui.value.grok.availableModels
        val meta = catalog.firstOrNull { it.id == model }
        val efforts = meta?.efforts ?: _ui.value.grok.effortOptions
        val resolvedEffort = if (effort in efforts.map { it.id }) effort else (meta?.defaultEffort ?: effort)
        val window = meta?.contextWindow?.takeIf { it > 0 } ?: _ui.value.grok.contextWindowTokens
        _ui.value = _ui.value.copy(
            grok = _ui.value.grok.copy(
                model = model,
                effort = resolvedEffort,
                permissionMode = permissionMode,
                effortOptions = efforts,
                contextWindowTokens = window,
                // Keep used tokens; recompute % against the new window.
                contextUsedPercentage = if (window > 0 && _ui.value.grok.contextUsedTokens > 0) {
                    _ui.value.grok.contextUsedTokens * 100.0 / window
                } else {
                    _ui.value.grok.contextUsedPercentage
                },
                contextRemainingPercentage = if (window > 0 && _ui.value.grok.contextUsedTokens > 0) {
                    (100.0 - _ui.value.grok.contextUsedTokens * 100.0 / window).coerceAtLeast(0.0)
                } else {
                    _ui.value.grok.contextRemainingPercentage
                },
            ),
        )
        viewModelScope.launch {
            settings.setGrokOptions(model, resolvedEffort, permissionMode)
            if (_ui.value.busy) {
                grokOptionsDirty = true
            } else {
                grokProcesses.values.forEach { it.stop() }
                grokProcesses.clear()
            }
        }
    }

    private suspend fun refreshClaudeAccountUsageInternal(force: Boolean) {
        if (!_ui.value.claudeCode.installed || !_ui.value.claudeCode.loggedIn) return
        claudeUsageRefreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - lastClaudeUsageRefreshAt < 55_000) return
            // The subscription usage endpoint throttles quickly. Do not turn an unavailable
            // quota read into a persistent background retry loop; the refresh button can still
            // make an explicit retry when the user chooses.
            if (!force && now - lastClaudeUsageFailureAt < 5 * 60_000) return
            val current = _ui.value.claudeCode
            _ui.value = _ui.value.copy(
                claudeCode = current.copy(accountUsageRefreshing = true, accountUsageError = null),
            )
            runCatching { claudeCodeUsage.fetch() }
                .onSuccess { usage ->
                    lastClaudeUsageRefreshAt = usage.fetchedAtMillis
                    _ui.value = _ui.value.copy(
                        claudeCode = _ui.value.claudeCode.copy(
                            fiveHourUsedPercentage = usage.fiveHour.usedPercentage,
                            fiveHourResetsAt = usage.fiveHour.resetsAtEpochSeconds,
                            sevenDayUsedPercentage = usage.sevenDay.usedPercentage,
                            sevenDayResetsAt = usage.sevenDay.resetsAtEpochSeconds,
                            modelScopedQuotas = usage.modelScoped,
                            accountUsageRefreshing = false,
                            accountUsageError = null,
                            telemetryUpdatedAt = usage.fetchedAtMillis,
                        ),
                    )
                }
                .onFailure { failure ->
                    lastClaudeUsageFailureAt = System.currentTimeMillis()
                    _ui.value = _ui.value.copy(
                        claudeCode = _ui.value.claudeCode.copy(
                            accountUsageRefreshing = false,
                            accountUsageError = failure.message?.take(180) ?: "Usage refresh failed",
                        ),
                    )
                }
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
        if (_ui.value.claudeCode.accountActionBusy) return
        viewModelScope.launch {
            startClaudeCodeLogin()
        }
    }

    /** Starts the CLI OAuth flow. Kept shared so account switching lands directly at sign-in. */
    private suspend fun startClaudeCodeLogin() {
        _ui.value = _ui.value.copy(
            claudeCode = _ui.value.claudeCode.copy(loginBusy = true, loginError = null, loginUrl = null),
        )
        runCatching {
            claudeCodeAuth.beginLogin { url ->
                _ui.value = _ui.value.copy(claudeCode = _ui.value.claudeCode.copy(loginUrl = url))
            }
        }.onSuccess { session ->
            claudeCodeLoginSession = session
            // Some Claude OAuth redirects complete the CLI without asking for a paste-back code.
            // Watch the process independently so that path actually updates the UI; submitCode only
            // feeds stdin and this waiter owns the final state transition.
            viewModelScope.launch {
                val ok = session.awaitResult()
                if (claudeCodeLoginSession !== session) return@launch
                claudeCodeLoginSession = null
                _ui.value = _ui.value.copy(
                    claudeCode = _ui.value.claudeCode.copy(
                        loginBusy = false,
                        loginUrl = null,
                        loginError = if (ok) null else session.diagnostic().ifBlank { "Sign-in did not complete; try again." },
                    ),
                )
                if (ok) checkClaudeCodeReadiness()
            }
        }.onFailure {
            _ui.value = _ui.value.copy(
                claudeCode = _ui.value.claudeCode.copy(
                    loginBusy = false,
                    loginError = "Couldn't start sign-in: ${it.message}",
                ),
            )
        }
    }

    /** Logs out of the current Claude account and clears account-bound live/resume state. */
    fun logoutClaudeCode() = changeClaudeCodeAccount(startLoginAfterLogout = false)

    /** Ends the current account session, then immediately opens Claude Code's OAuth flow for another account. */
    fun switchClaudeCodeAccount() = changeClaudeCodeAccount(startLoginAfterLogout = true)

    private fun changeClaudeCodeAccount(startLoginAfterLogout: Boolean) {
        if (_ui.value.claudeCode.accountActionBusy || _ui.value.claudeCode.loginBusy) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                claudeCode = _ui.value.claudeCode.copy(accountActionBusy = true, accountActionError = null),
            )
            runCatching {
                // A running process has the old account's token in memory. Stop and await its job
                // before invoking `claude auth logout`, then discard every persisted --resume id.
                streamJob?.cancelAndJoin()
                streamJob = null
                claudeCodeProcesses.values.forEach { it.stop() }
                claudeCodeProcesses.clear()
                claudeCodeOptionsDirty = false
                claudeCodeSessionDao.clearAll()
                claudeCodeAuth.logout()
            }.onSuccess {
                val current = _ui.value.claudeCode
                _ui.value = _ui.value.copy(
                    claudeCode = current.copy(
                        loggedIn = false,
                        email = null,
                        subscriptionType = null,
                        accountActionBusy = false,
                        accountActionError = null,
                        sessionCostUsd = 0.0,
                        lastTurnInputTokens = 0,
                        lastTurnOutputTokens = 0,
                        lastTurnCacheReadTokens = 0,
                        lastTurnCacheCreationTokens = 0,
                        contextUsedTokens = 0,
                        contextWindowTokens = 0,
                        contextUsedPercentage = null,
                        contextRemainingPercentage = null,
                        fiveHourUsedPercentage = null,
                        fiveHourResetsAt = null,
                        sevenDayUsedPercentage = null,
                        sevenDayResetsAt = null,
                        accountUsageError = null,
                    ),
                )
                lastClaudeUsageRefreshAt = 0L
                if (startLoginAfterLogout) startClaudeCodeLogin()
            }.onFailure { error ->
                _ui.value = _ui.value.copy(
                    claudeCode = _ui.value.claudeCode.copy(
                        accountActionBusy = false,
                        accountActionError = "Couldn't sign out: ${error.message ?: "Unknown error"}",
                    ),
                )
                checkClaudeCodeReadiness()
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

    /** Stops an abandoned browser OAuth flow without changing the current account. */
    fun cancelClaudeCodeLogin() {
        claudeCodeLoginSession?.cancel()
        claudeCodeLoginSession = null
        _ui.value = _ui.value.copy(
            claudeCode = _ui.value.claudeCode.copy(loginBusy = false, loginUrl = null, loginError = null),
        )
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
        if ((text.isEmpty() && state.attachments.isEmpty()) || state.busy) return

        if (state.mode.isAgentCli) {
            viewModelScope.launch {
                _ui.value = state.copy(input = "", attachments = emptyList(), busy = true, error = null, notice = null)
                val chatId = chatIdFlow.value ?: chatRepo.createChat(PLACEHOLDER_TITLE, modeString(state.mode))
                    .also {
                        chatIdFlow.value = it.id
                        _ui.value = _ui.value.copy(activeChatId = it.id)
                    }.id
                chatRepo.addUserMessage(chatId, text, state.attachments)
                // Attachment paths are described the same way for both engines: they are just real files
                // inside the shared project sandbox, and both CLIs read them with their own file tools.
                val prompt = claudePromptWithAttachments(text, state.attachments)
                conversation.add(ChatMessage(ChatMessage.Role.USER, prompt))
                when (state.mode) {
                    ChatMode.CODEX -> runCodex(chatId, prompt)
                    ChatMode.GROK -> runGrok(chatId, prompt)
                    else -> runClaudeCode(chatId, prompt)
                }
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

    private fun claudePromptWithAttachments(text: String, attachments: List<ChatAttachment>): String {
        if (attachments.isEmpty()) return text
        return buildString {
            append(text.ifBlank { "Please inspect the attached file(s)." })
            append("\n\nAttached files are available inside the project sandbox. Read/analyze them directly:")
            attachments.forEach { attachment ->
                append("\n- ").append(attachment.displayName)
                    .append(" (").append(attachment.mimeType ?: "file").append("): ")
                    .append(attachment.claudePath)
            }
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
        _ui.value = _ui.value.copy(projectName = projects.get(pid)?.name)
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
            val workId = "agent:$pid"
            DevelopmentWorkService.acquire(appContext, workId, "Orion agent · ${_ui.value.projectName ?: "project"}")
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
                DevelopmentWorkService.release(appContext, workId)
                throw c
            } catch (t: Throwable) {
                errorMsg = t.message ?: "agent run failed"
            }
            DevelopmentWorkService.release(appContext, workId)
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
            toolBridgePort = claudeToolBridge.start(),
            toolBridgeToken = claudeToolBridge.token,
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
        _ui.value = _ui.value.copy(projectName = projects.get(pid)?.name)
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
            val workId = "claude:$pid"
            DevelopmentWorkService.acquire(appContext, workId, "Claude Code · ${_ui.value.projectName ?: "project"}")
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
                    val live = mapper.liveContextUsage
                    val window = live.contextWindowTokens.takeIf { it > 0 }
                        ?: _ui.value.claudeCode.contextWindowTokens.takeIf { it > 0 }
                        ?: 0L
                    val usedPct = if (window > 0) live.usedTokens * 100.0 / window else null
                    val fiveHour = mapper.fiveHourRateLimit
                    val sevenDay = mapper.sevenDayRateLimit
                    _ui.value = _ui.value.copy(
                        claudeCode = _ui.value.claudeCode.copy(
                            contextUsedTokens = live.usedTokens,
                            contextWindowTokens = window,
                            contextUsedPercentage = usedPct,
                            contextRemainingPercentage = usedPct?.let { (100.0 - it).coerceAtLeast(0.0) },
                            fiveHourUsedPercentage = fiveHour?.usedPercentage
                                ?: _ui.value.claudeCode.fiveHourUsedPercentage,
                            fiveHourResetsAt = fiveHour?.resetsAtEpochSeconds
                                ?: _ui.value.claudeCode.fiveHourResetsAt,
                            sevenDayUsedPercentage = sevenDay?.usedPercentage
                                ?: _ui.value.claudeCode.sevenDayUsedPercentage,
                            sevenDayResetsAt = sevenDay?.resetsAtEpochSeconds
                                ?: _ui.value.claudeCode.sevenDayResetsAt,
                        ),
                    )
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
                DevelopmentWorkService.release(appContext, workId)
                throw c
            } catch (t: Throwable) {
                errorMsg = t.message ?: "Claude Code run failed"
            }
            DevelopmentWorkService.release(appContext, workId)
            val status = if (errorMsg == null) MsgStatus.COMPLETE else MsgStatus.ERROR
            chatRepo.finishAssistantParts(chatId, assistantId, mapper.currentParts, status)
            if (!resultText.isNullOrBlank()) conversation.add(ChatMessage(ChatMessage.Role.ASSISTANT, resultText!!))
            streamingFlow.value = null
            _ui.value = _ui.value.copy(busy = false, error = errorMsg)
            if (status == MsgStatus.COMPLETE) withContext(NonCancellable) { maybeNameClaudeCodeChatAndProject(chatId, pid, isFreshProject, userText) }
            refreshClaudeAccountUsageInternal(force = false)
            if (claudeCodeOptionsDirty) {
                claudeCodeOptionsDirty = false
                claudeCodeProcesses.values.forEach { it.stop() }
                claudeCodeProcesses.clear()
            }
        }
    }

    // ── Codex (real CLI) ──

    /** Reuses (or lazily creates) the per-project Codex driver. Unlike the Claude equivalent this holds
     * no OS process between turns — `codex exec` is one-shot — so this map exists only to avoid rebuilding
     * the same argument set and to give [setCodexOptions] something to invalidate. */
    private suspend fun codexSessionFor(projectId: String, projectDir: File): CodexProcess {
        codexProcesses[projectId]?.let { return it }
        val existing = codexSessionDao.get(projectId)
        val cx = _ui.value.codex
        val process = CodexProcess(
            toolchain = codexToolchain,
            projectDir = projectDir,
            threadId = existing?.threadId,
            onThreadIdAssigned = { tid ->
                viewModelScope.launch {
                    codexSessionDao.upsert(CodexSessionEntity(projectId, tid, System.currentTimeMillis()))
                }
            },
            model = cx.model,
            effort = cx.effort,
            sandbox = cx.sandbox,
            toolBridgePort = claudeToolBridge.start(),
            toolBridgeToken = claudeToolBridge.token,
        )
        codexProcesses[projectId] = process
        return process
    }

    /**
     * Runs one Codex turn. Intentionally the same shape as [runClaudeCode] — blank-project creation under
     * the shared mutex, a GitSnapshotter safety snapshot before handing over control, the foreground work
     * service, the fixed preview port, auto-naming on first success — because everything except the
     * engine itself is genuinely shared behaviour, and the two paths diverging would show up as one
     * engine quietly missing rollback or preview.
     */
    private suspend fun runCodex(chatId: String, userText: String) {
        val isFreshProject = projectId == null
        if (isFreshProject) {
            projectResolutionMutex.withLock {
                if (projectId == null) projectId = projects.createBlankProject("New Project").id
            }
        }
        val pid = projectId!!
        activeProject.set(pid)
        chatRepo.bindChatProject(chatId, pid, "codex")
        _ui.value = _ui.value.copy(projectName = projects.get(pid)?.name)
        val dir = projects.dirFor(pid)

        val status = codexToolchain.status()
        if (!status.provisioned) {
            _ui.value = _ui.value.copy(
                busy = false,
                error = if (status.needsOnlyCodexBinary) {
                    "Codex isn't downloaded yet — open the mode picker to install it."
                } else {
                    "The Linux runtime isn't installed yet — open the mode picker to install it."
                },
            )
            return
        }
        val auth = runCatching { codexAuth.status() }.getOrNull()
        if (auth?.loggedIn != true) {
            _ui.value = _ui.value.copy(busy = false, error = "Sign in to Codex first — open the mode picker to sign in.")
            return
        }

        // Same fixed preview port as Claude Code, and the same instructions telling the agent to use it
        // (both engines receive ORION_AGENT_INSTRUCTIONS), so the Preview pane behaves identically.
        devServerState.set(com.legion.orion.runtime.DevServerState.Running(ClaudeCodeProcess.PREVIEW_PORT, dir.path))

        val snapshotter = GitSnapshotter(dir)
        val snapshotRef = runCatching { snapshotter.snapshot(pid, "codex_${System.currentTimeMillis()}") }.getOrNull()

        val process = codexSessionFor(pid, dir)
        val assistantId = chatRepo.beginAssistantMessage(chatId)
        val mapper = CodexEventMapper()
        streamingFlow.value = StreamingMessage(assistantId, mapper.currentParts)

        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val workId = "codex:$pid"
            DevelopmentWorkService.acquire(appContext, workId, "Codex · ${_ui.value.projectName ?: "project"}")
            var errorMsg: String? = null
            var resultText: String? = null
            try {
                process.sendTurn(userText).collect { obj ->
                    when (val outcome = mapper.accept(obj)) {
                        is CodexEventMapper.Outcome.TurnComplete -> {
                            resultText = outcome.resultText
                            if (outcome.isError) errorMsg = outcome.resultText ?: "Codex reported an error"
                            outcome.usage?.let { u ->
                                _ui.value = _ui.value.copy(
                                    codex = _ui.value.codex.copy(
                                        lastTurnInputTokens = u.inputTokens,
                                        lastTurnOutputTokens = u.outputTokens,
                                        lastTurnCacheReadTokens = u.cacheReadTokens,
                                    ),
                                )
                            }
                        }
                        is CodexEventMapper.Outcome.ProcessDied -> errorMsg = outcome.message
                        CodexEventMapper.Outcome.InProgress -> {}
                    }
                    val live = mapper.liveContextUsage
                    _ui.value = _ui.value.copy(
                        codex = _ui.value.codex.copy(
                            contextUsedTokens = live.usedTokens,
                            contextWindowTokens = live.contextWindowTokens.takeIf { it > 0 }
                                ?: _ui.value.codex.contextWindowTokens,
                        ),
                    )
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
                DevelopmentWorkService.release(appContext, workId)
                throw c
            } catch (t: Throwable) {
                errorMsg = t.message ?: "Codex run failed"
            }
            DevelopmentWorkService.release(appContext, workId)
            val status2 = if (errorMsg == null) MsgStatus.COMPLETE else MsgStatus.ERROR
            chatRepo.finishAssistantParts(chatId, assistantId, mapper.currentParts, status2)
            if (!resultText.isNullOrBlank()) conversation.add(ChatMessage(ChatMessage.Role.ASSISTANT, resultText!!))
            streamingFlow.value = null
            _ui.value = _ui.value.copy(busy = false, error = errorMsg)
            if (status2 == MsgStatus.COMPLETE) {
                withContext(NonCancellable) { maybeNameClaudeCodeChatAndProject(chatId, pid, isFreshProject, userText) }
            }
            if (codexOptionsDirty) {
                codexOptionsDirty = false
                codexProcesses.values.forEach { it.stop() }
                codexProcesses.clear()
            }
        }
    }

    // ── Grok Build (real CLI) ──

    private suspend fun grokSessionFor(projectId: String, projectDir: File): GrokProcess {
        grokProcesses[projectId]?.let { return it }
        val existing = grokSessionDao.get(projectId)
        val gx = _ui.value.grok
        val process = GrokProcess(
            toolchain = grokToolchain,
            projectDir = projectDir,
            sessionId = existing?.sessionId,
            onSessionIdAssigned = { sid ->
                viewModelScope.launch {
                    grokSessionDao.upsert(GrokSessionEntity(projectId, sid, System.currentTimeMillis()))
                }
            },
            model = gx.model,
            effort = gx.effort,
            permissionMode = gx.permissionMode,
            toolBridgePort = claudeToolBridge.start(),
            toolBridgeToken = claudeToolBridge.token,
        )
        grokProcesses[projectId] = process
        return process
    }

    private suspend fun runGrok(chatId: String, userText: String) {
        val isFreshProject = projectId == null
        if (isFreshProject) {
            projectResolutionMutex.withLock {
                if (projectId == null) projectId = projects.createBlankProject("New Project").id
            }
        }
        val pid = projectId!!
        activeProject.set(pid)
        chatRepo.bindChatProject(chatId, pid, "grok")
        _ui.value = _ui.value.copy(projectName = projects.get(pid)?.name)
        val dir = projects.dirFor(pid)

        val status = grokToolchain.status()
        if (!status.provisioned) {
            _ui.value = _ui.value.copy(
                busy = false,
                error = if (status.needsOnlyGrokBinary) {
                    "Grok Build isn't downloaded yet — open the mode picker to install it."
                } else {
                    "The Linux runtime isn't installed yet — open the mode picker to install it."
                },
            )
            return
        }
        val auth = runCatching { grokAuth.status() }.getOrNull()
        if (auth?.loggedIn != true) {
            _ui.value = _ui.value.copy(busy = false, error = "Sign in to Grok Build first — open the mode picker to sign in.")
            return
        }

        devServerState.set(com.legion.orion.runtime.DevServerState.Running(ClaudeCodeProcess.PREVIEW_PORT, dir.path))

        val snapshotter = GitSnapshotter(dir)
        val snapshotRef = runCatching { snapshotter.snapshot(pid, "grok_${System.currentTimeMillis()}") }.getOrNull()

        val process = grokSessionFor(pid, dir)
        val assistantId = chatRepo.beginAssistantMessage(chatId)
        val mapper = GrokEventMapper()
        streamingFlow.value = StreamingMessage(assistantId, mapper.currentParts)

        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val workId = "grok:$pid"
            DevelopmentWorkService.acquire(appContext, workId, "Grok Build · ${_ui.value.projectName ?: "project"}")
            var errorMsg: String? = null
            var resultText: String? = null
            try {
                process.sendTurn(userText).collect { obj ->
                    when (val outcome = mapper.accept(obj)) {
                        is GrokEventMapper.Outcome.TurnComplete -> {
                            resultText = outcome.resultText
                            if (outcome.isError) errorMsg = outcome.resultText ?: "Grok Build reported an error"
                            outcome.usage?.let { u ->
                                _ui.value = _ui.value.copy(
                                    grok = _ui.value.grok.copy(
                                        sessionCostUsd = _ui.value.grok.sessionCostUsd + u.costUsd,
                                        lastTurnInputTokens = u.inputTokens,
                                        lastTurnOutputTokens = u.outputTokens,
                                        lastTurnCacheReadTokens = u.cacheReadTokens,
                                    ),
                                )
                            }
                        }
                        is GrokEventMapper.Outcome.ProcessDied -> errorMsg = outcome.message
                        GrokEventMapper.Outcome.InProgress -> {}
                    }
                    // Prefer explicit context fields from the stream; otherwise keep session signals.
                    val live = mapper.liveContextUsage
                    val window = live.contextWindowTokens.takeIf { it > 0 }
                        ?: _ui.value.grok.contextWindowTokens.takeIf { it > 0 }
                        ?: 500_000L
                    val used = live.usedTokens.takeIf { it > 0 } ?: _ui.value.grok.contextUsedTokens
                    val pct = if (window > 0 && used > 0) used * 100.0 / window else _ui.value.grok.contextUsedPercentage
                    _ui.value = _ui.value.copy(
                        grok = _ui.value.grok.copy(
                            contextUsedTokens = used,
                            contextWindowTokens = window,
                            contextUsedPercentage = pct,
                            contextRemainingPercentage = pct?.let { (100.0 - it).coerceAtLeast(0.0) },
                        ),
                    )
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
                DevelopmentWorkService.release(appContext, workId)
                throw c
            } catch (t: Throwable) {
                errorMsg = t.message ?: "Grok Build run failed"
            }
            DevelopmentWorkService.release(appContext, workId)
            val status2 = if (errorMsg == null) MsgStatus.COMPLETE else MsgStatus.ERROR
            chatRepo.finishAssistantParts(chatId, assistantId, mapper.currentParts, status2)
            if (!resultText.isNullOrBlank()) conversation.add(ChatMessage(ChatMessage.Role.ASSISTANT, resultText!!))
            streamingFlow.value = null
            _ui.value = _ui.value.copy(busy = false, error = errorMsg)
            // Re-read session signals + billing so context / monthly limit stay accurate after the turn.
            runCatching { refreshGrokAccountUsageInternal(force = true) }
            if (status2 == MsgStatus.COMPLETE) {
                withContext(NonCancellable) { maybeNameClaudeCodeChatAndProject(chatId, pid, isFreshProject, userText) }
            }
            if (grokOptionsDirty) {
                grokOptionsDirty = false
                grokProcesses.values.forEach { it.stop() }
                grokProcesses.clear()
            }
        }
    }

    // ── Engine handoff ──

    /**
     * Moves the current chat from one real CLI to another, seeding the incoming engine with a brief
     * describing what has happened so far. The usual trigger is one account running out of rate-limit
     * headroom mid-task.
     *
     * Session ids are not portable across Claude / Codex / Grok stores — this starts a fresh session
     * in the target engine and hands over a summary; the working tree is already shared.
     */
    fun switchEngine(to: ChatMode) {
        if (!to.isAgentCli) return
        val from = _ui.value.mode
        if (from == to) return
        if (_ui.value.busy) {
            _ui.value = _ui.value.copy(error = "Wait for the current turn to finish before switching engines.")
            return
        }

        viewModelScope.launch {
            val chatId = chatIdFlow.value
            if (chatId == null) {
                setMode(to)
                return@launch
            }

            _ui.value = _ui.value.copy(mode = to, error = null)
            chatRepo.bindChatProject(chatId, projectId, modeString(to))
            when (to) {
                ChatMode.CODEX -> checkCodexReadiness()
                ChatMode.GROK -> checkGrokReadiness()
                else -> checkClaudeCodeReadiness()
            }

            val brief = buildHandoffBrief(from, to)
            if (brief != null) {
                _ui.value = _ui.value.copy(
                    input = brief,
                    notice = "Switched to ${engineLabel(to)}. A handoff summary is in the input box — edit or send it.",
                )
            } else {
                _ui.value = _ui.value.copy(notice = "Switched to ${engineLabel(to)}.")
            }
        }
    }

    private fun engineLabel(mode: ChatMode): String = when (mode) {
        ChatMode.CODEX -> "Codex"
        ChatMode.CLAUDE_CODE -> "Claude Code"
        ChatMode.GROK -> "Grok Build"
        ChatMode.BUILD -> "Build"
        ChatMode.CHAT -> "Chat"
    }

    /**
     * Builds the text handed to the incoming engine.
     *
     * Deliberately a compaction rather than a verbatim replay: a long agentic transcript is mostly tool
     * output (file dumps, build logs) that the new engine can simply re-read from the working tree, and
     * pasting all of it would spend a large share of the fresh context window restating things that are
     * already on disk. Assistant text and user intent are what cannot be recovered from the filesystem,
     * so those are what get kept.
     */
    private fun buildHandoffBrief(from: ChatMode, to: ChatMode): String? {
        if (conversation.isEmpty()) return null
        val recent = conversation.takeLast(HANDOFF_MESSAGE_COUNT)
        return buildString {
            append("Context handoff: this conversation was being handled by ")
            append(engineLabel(from))
            append(" and is continuing with ")
            append(engineLabel(to))
            append(".\n\nYou are picking up mid-task in an existing project at /root/project. ")
            append("The working tree and its git history already reflect all work done so far, so inspect ")
            append("the actual files before assuming anything from the summary below.\n\n")
            append("Conversation so far (most recent ").append(recent.size).append(" messages")
            if (conversation.size > recent.size) append(" of ").append(conversation.size)
            append("):\n")
            recent.forEach { message ->
                val who = if (message.role == ChatMessage.Role.USER) "User" else "Assistant"
                append("\n[").append(who).append("] ")
                append(message.content.trim().take(HANDOFF_MESSAGE_CHAR_LIMIT))
                if (message.content.trim().length > HANDOFF_MESSAGE_CHAR_LIMIT) append(" …[truncated]")
                append('\n')
            }
            append("\nContinue from here. Start by confirming the current state of the code rather than ")
            append("repeating completed work.")
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
            mode = chatModeOf(chat.mode),
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
        if (chat.mode == "codex") checkCodexReadiness()
        if (chat.mode == "grok") checkGrokReadiness()
    }

    /** Explicitly continues an existing project in the CURRENT chat (project picker "Continue" pick)
     * — this is the intentional counterpart to the old silent-reuse bug: the user is choosing to
     * resume this project's Claude Code session, not having it happen to them by accident. */
    fun selectProject(project: com.legion.orion.core.database.ProjectEntity) {
        projectId = project.id
        activeProject.set(project.id)
        _ui.value = _ui.value.copy(projectName = project.name)
        if (_ui.value.mode.isAgentCli) {
            devServerState.set(com.legion.orion.runtime.DevServerState.Running(ClaudeCodeProcess.PREVIEW_PORT, project.path))
        }
        viewModelScope.launch {
            chatIdFlow.value?.let { chatId ->
                chatRepo.bindChatProject(chatId, project.id, modeString(_ui.value.mode))
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

    /** Project picker "Just chat with Claude" pick — Claude Code runs with no real project bound,
     * implemented as a singleton pseudo-project (see [ProjectManager.CHAT_ONLY_PROJECT_ID]) rather
     * than new state-tracking, so it's just [selectProject] with that project like any other. */
    fun selectJustChat() {
        viewModelScope.launch {
            val chatOnly = projects.ensureChatOnlyProject()
            selectProject(chatOnly)
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
        // Codex holds no process between turns, so this only kills a turn that is actually in flight;
        // its thread id is already persisted, so the next send resumes the same conversation.
        codexProcesses.values.forEach { it.stop() }
        codexLoginSession?.cancel()
    }
}
