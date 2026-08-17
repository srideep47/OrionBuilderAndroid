package com.legion.orion.marta

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.data.chat.ChatRepository
import com.legion.orion.data.chat.MsgStatus
import com.legion.orion.data.chat.PartType
import com.legion.orion.data.chat.UiMessage
import com.legion.orion.data.chat.UiPart
import com.legion.orion.data.marketplace.ModelDownloader
import com.legion.orion.data.project.ActiveProjectStore
import com.legion.orion.data.project.ProjectManager
import com.legion.orion.engine.agent.AgentConfig
import com.legion.orion.engine.agent.AgentEvent
import com.legion.orion.engine.agent.AgentLoop
import com.legion.orion.engine.agent.NoopSnapshotter
import com.legion.orion.engine.agent.ToolRegistry
import com.legion.orion.engine.agent.tools.DefaultTools
import com.legion.orion.engine.providers.ChatMessage
import com.legion.orion.engine.providers.LocalMnnProvider
import com.legion.orion.inference.EngineState
import com.legion.orion.inference.InferenceController
import com.legion.orion.internet.DevIdentityStore
import com.legion.orion.internet.InternetAgentTools
import com.legion.orion.internet.InternetManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MartaUi(
    val ready: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val call: Boolean = false,
    val phase: MartaVoice.Phase = MartaVoice.Phase.IDLE,
    val input: String = "",
    val partial: String = "",
    val status: String = "Marta is waking up",
    val error: String? = null,
    val level: Float = 0f,
    val liveText: String = "",
)

@HiltViewModel
class MartaViewModel @Inject constructor(
    application: Application,
    private val appControl: AppControl,
    private val appTools: AppControlTools,
    private val inference: InferenceController,
    private val chats: ChatRepository,
    private val projects: ProjectManager,
    private val activeProject: ActiveProjectStore,
    private val downloader: ModelDownloader,
    internet: InternetManager,
    identities: DevIdentityStore,
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(MartaUi())
    val ui = _ui.asStateFlow()

    val transcript = MutableStateFlow<List<UiMessage>>(emptyList())

    val engine = inference.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), inference.state.value)

    private val conversation = mutableListOf<ChatMessage>()
    private var turnJob: Job? = null
    private var chatId: String? = null
    private var voice: MartaVoice? = null
    private val registry = ToolRegistry(
        appTools.list() +
            InternetAgentTools.list(internet, identities) +
            DefaultTools.list().filter { it.name in ALLOWED_FILES },
    )

    init {
        viewModelScope.launch { boot() }
    }

    private suspend fun boot() {
        _ui.value = _ui.value.copy(loading = true, status = "Looking for Marta’s 2B model")
        runCatching { downloader.scanLocalModels() }
        val existing = chats.observeChats().first().firstOrNull { it.mode == "marta" }
        val chat = existing ?: chats.createChat("Marta", "marta")
        chatId = chat.id
        viewModelScope.launch {
            chats.observeMessages(chat.id).collect { transcript.value = it }
        }
        val dir = appControl.martaModelDir()
        if (dir == null) {
            _ui.value = _ui.value.copy(
                loading = false,
                ready = false,
                status = "Qwen3.5-2B-MNN is not on the phone yet",
                error = "Push the 2B model into Orion’s models folder, then tap Retry.",
            )
            return
        }
        val already = inference.state.value.let {
            it.status == EngineState.Status.READY && it.modelDir?.endsWith(MartaProfile.MODEL_DIR_NAME) == true
        }
        if (!already) {
            _ui.value = _ui.value.copy(status = "Loading Qwen3.5-2B on CPU…")
            runCatching { appControl.loadMartaModel(voice = false) }
                .onFailure {
                    _ui.value = _ui.value.copy(loading = false, error = it.message, status = "Load failed")
                    return
                }
        }
        _ui.value = _ui.value.copy(loading = false, ready = true, error = null, status = "On a call, or type — I’m here")
    }

    fun retryLoad() { viewModelScope.launch { boot() } }

    fun onInput(value: String) { _ui.value = _ui.value.copy(input = value) }

    fun sendText() {
        val text = _ui.value.input.trim()
        if (text.isEmpty() || _ui.value.busy) return
        _ui.value = _ui.value.copy(input = "")
        speakTurn(text, spoken = false)
    }

    fun toggleCall() {
        if (_ui.value.call) hangUp() else startCall()
    }

    fun startCall() {
        if (_ui.value.call) return
        val v = voice ?: MartaVoice(getApplication(), viewModelScope, voiceListener).also { voice = it }
        _ui.value = _ui.value.copy(call = true, status = "Listening")
        viewModelScope.launch { runCatching { appControl.loadMartaModel(voice = true) } }
        v.startCall()
    }

    fun hangUp() {
        voice?.stopCall()
        turnJob?.cancel()
        _ui.value = _ui.value.copy(call = false, phase = MartaVoice.Phase.IDLE, partial = "", status = "Call ended", busy = false)
    }

    fun stopTurn() {
        turnJob?.cancel()
        voice?.cancelSpeech()
        _ui.value = _ui.value.copy(busy = false, liveText = "")
    }

    private val voiceListener = object : MartaVoice.Listener {
        override fun onPhase(phase: MartaVoice.Phase) {
            _ui.value = _ui.value.copy(phase = phase, status = when (phase) {
                MartaVoice.Phase.IDLE -> if (_ui.value.call) "On a call" else "Ready"
                MartaVoice.Phase.LISTENING -> "Listening"
                MartaVoice.Phase.HEARING -> "Hearing you"
                MartaVoice.Phase.SPEAKING -> "Speaking"
            })
        }
        override fun onPartialTranscript(text: String) { _ui.value = _ui.value.copy(partial = text) }
        override fun onFinalTranscript(text: String) {
            _ui.value = _ui.value.copy(partial = "")
            speakTurn(text, spoken = true)
        }
        override fun onBargeIn() {
            turnJob?.cancel()
            _ui.value = _ui.value.copy(busy = false, liveText = "", status = "I’m listening")
        }
        override fun onTtsIdle() {
            if (_ui.value.call) _ui.value = _ui.value.copy(status = "Listening")
        }
        override fun onVoiceError(message: String) { _ui.value = _ui.value.copy(error = message) }
        override fun onLevel(rms: Float) { _ui.value = _ui.value.copy(level = rms) }
    }

    private fun speakTurn(userText: String, spoken: Boolean) {
        val id = chatId ?: return
        if (!inference.isLoaded) {
            _ui.value = _ui.value.copy(error = "Marta’s model is not loaded")
            return
        }
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null, liveText = "")
            chats.addUserMessage(id, userText)
            conversation.add(ChatMessage(ChatMessage.Role.USER, userText))
            val project = projects.ensureChatOnlyProject()
            activeProject.set(project.id)
            val loop = AgentLoop(
                provider = LocalMnnProvider(inference),
                registry = registry,
                snapshotter = NoopSnapshotter,
                config = AgentConfig(
                    model = "marta-2b",
                    maxIterations = MartaProfile.AGENT_ITERS,
                    contextWindowTokens = MartaProfile.CONTEXT_TOKENS,
                ),
                projectDir = projects.dirFor(project.id),
                systemPrompt = MartaPrompts.SYSTEM,
            )
            val assistantId = chats.beginAssistantMessage(id)
            val parts = mutableListOf<UiPart>()
            val live = StringBuilder()
            var status = MsgStatus.COMPLETE
            try {
                loop.run(chatId = id, history = conversation.toList()).collect { ev ->
                    when (ev) {
                        is AgentEvent.TextDelta -> {
                            live.append(ev.text)
                            appendText(parts, ev.text)
                            _ui.value = _ui.value.copy(liveText = live.toString())
                            if (spoken || _ui.value.call) voice?.feedText(ev.text)
                        }
                        is AgentEvent.ThinkingDelta -> { /* thinking is off */ }
                        is AgentEvent.AssistantMessage -> {}
                        is AgentEvent.ToolStarted -> {
                            parts += UiPart(PartType.TOOL_CALL, "", com.legion.orion.data.chat.ToolView(ev.name, ev.argsJson, "running"))
                        }
                        is AgentEvent.ToolFinished -> {
                            val idx = parts.indexOfLast { it.type == PartType.TOOL_CALL && it.tool?.name == ev.name && it.tool?.status == "running" }
                            if (idx >= 0) {
                                val prev = parts[idx]
                                parts[idx] = prev.copy(tool = prev.tool?.copy(status = if (ev.result.isError) "error" else "done", result = ev.result.content))
                            }
                        }
                        is AgentEvent.Compacted -> {}
                        AgentEvent.TurnComplete -> {}
                        is AgentEvent.Failed -> {
                            status = MsgStatus.ERROR
                            _ui.value = _ui.value.copy(error = ev.message)
                        }
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                status = MsgStatus.CANCELLED
                throw c
            } catch (t: Throwable) {
                status = MsgStatus.ERROR
                _ui.value = _ui.value.copy(error = t.message)
            } finally {
                val visible = parts.filter { it.type == PartType.TEXT }.joinToString("") { it.text }
                if (visible.isNotBlank()) conversation.add(ChatMessage(ChatMessage.Role.ASSISTANT, visible))
                chats.finishAssistantParts(id, assistantId, parts, status)
                if (spoken || _ui.value.call) voice?.finishSpeaking()
                _ui.value = _ui.value.copy(busy = false)
            }
        }
    }

    private fun appendText(parts: MutableList<UiPart>, delta: String) {
        val last = parts.lastOrNull()
        if (last != null && last.type == PartType.TEXT) {
            parts[parts.lastIndex] = last.copy(text = last.text + delta)
        } else {
            parts += UiPart(PartType.TEXT, delta)
        }
    }

    override fun onCleared() {
        voice?.release()
        super.onCleared()
    }

    companion object {
        private val ALLOWED_FILES = setOf("read_file", "list_files", "grep", "get_repo_map")
    }
}
