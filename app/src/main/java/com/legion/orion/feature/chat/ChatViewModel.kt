package com.legion.orion.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.core.database.ProviderEntity
import com.legion.orion.data.chat.ChatRepository
import com.legion.orion.data.chat.MsgStatus
import com.legion.orion.data.chat.MsgRole
import com.legion.orion.data.chat.UiMessage
import com.legion.orion.data.chat.UiPart
import com.legion.orion.data.chat.PartType
import com.legion.orion.data.providers.ProviderRepository
import com.legion.orion.data.providers.ProviderResolver
import com.legion.orion.data.providers.ResolveResult
import com.legion.orion.core.datastore.SettingsRepository
import com.legion.orion.engine.providers.ChatMessage
import com.legion.orion.engine.providers.ChatRequest
import com.legion.orion.engine.providers.StreamEvent
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

data class StreamingMessage(
    val messageId: String,
    val text: String = "",
    val thinking: String = "",
)

data class ChatUiState(
    val providers: List<ProviderEntity> = emptyList(),
    val selectedProvider: ProviderEntity? = null,
    val selectedModel: String = "",
    val input: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    private val providerRepo: ProviderRepository,
    private val resolver: ProviderResolver,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState())
    val ui = _ui.asStateFlow()

    private val chatIdFlow = MutableStateFlow<String?>(null)
    private val streamingFlow = MutableStateFlow<StreamingMessage?>(null)

    /** In-memory conversation (text only) used to build provider requests. */
    private val conversation = mutableListOf<ChatMessage>()
    private var streamJob: Job? = null

    /** Stored messages for the current chat, with the in-flight streaming turn overlaid. */
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

            // Resume the most recent chat so history survives restart.
            val recent = chatRepo.observeChats().first().firstOrNull()
            if (recent != null) {
                chatIdFlow.value = recent.id
                rebuildConversationFrom(recent.id)
            }
        }
    }

    fun onInput(text: String) { _ui.value = _ui.value.copy(input = text) }

    fun selectProvider(provider: ProviderEntity, model: String) {
        _ui.value = _ui.value.copy(selectedProvider = provider, selectedModel = model, error = null)
        viewModelScope.launch { settings.setSelectedProvider(provider.id, model) }
    }

    /** Picks a provider and auto-selects its default (first) model. */
    fun chooseProvider(provider: ProviderEntity) {
        viewModelScope.launch {
            val model = providerRepo.modelsFor(provider.id).firstOrNull()?.modelId ?: ""
            selectProvider(provider, model)
        }
    }

    fun send() {
        val state = _ui.value
        val provider = state.selectedProvider ?: run {
            _ui.value = state.copy(error = "Select a provider first")
            return
        }
        val text = state.input.trim()
        if (text.isEmpty() || state.busy) return

        viewModelScope.launch {
            _ui.value = state.copy(input = "", busy = true, error = null)

            val chatId = chatIdFlow.value ?: chatRepo.createChat(text.take(40)).also {
                chatIdFlow.value = it.id
            }.id

            chatRepo.addUserMessage(chatId, text)
            conversation.add(ChatMessage(ChatMessage.Role.USER, text))

            when (val resolved = resolver.resolve(provider)) {
                is ResolveResult.MissingKey -> {
                    _ui.value = _ui.value.copy(busy = false, error = "Add an API key for ${provider.label} in Settings")
                    return@launch
                }
                is ResolveResult.Misconfigured -> {
                    _ui.value = _ui.value.copy(busy = false, error = resolved.reason)
                    return@launch
                }
                is ResolveResult.Ready -> {
                    val assistantId = chatRepo.beginAssistantMessage(chatId)
                    streamingFlow.value = StreamingMessage(assistantId)
                    val request = ChatRequest(
                        model = state.selectedModel,
                        messages = conversation.toList(),
                        system = SYSTEM_PROMPT,
                    )
                    runStream(chatId, assistantId, resolved.provider.stream(request))
                }
            }
        }
    }

    private fun runStream(
        chatId: String,
        assistantId: String,
        events: kotlinx.coroutines.flow.Flow<StreamEvent>,
    ) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val textBuf = StringBuilder()
            val thinkBuf = StringBuilder()
            var errorMsg: String? = null
            try {
                events.collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            textBuf.append(event.text)
                            streamingFlow.value = StreamingMessage(assistantId, textBuf.toString(), thinkBuf.toString())
                        }
                        is StreamEvent.ThinkingDelta -> {
                            thinkBuf.append(event.text)
                            streamingFlow.value = StreamingMessage(assistantId, textBuf.toString(), thinkBuf.toString())
                        }
                        is StreamEvent.Error -> errorMsg = event.message
                        else -> {} // Usage / tool events ignored in M1
                    }
                }
            } catch (c: CancellationException) {
                withContext(NonCancellable) {
                    finalize(chatId, assistantId, textBuf, thinkBuf, MsgStatus.CANCELLED, null)
                }
                throw c
            } catch (t: Throwable) {
                errorMsg = t.message ?: "stream failed"
            }
            val status = if (errorMsg == null) MsgStatus.COMPLETE else MsgStatus.ERROR
            finalize(chatId, assistantId, textBuf, thinkBuf, status, errorMsg)
        }
    }

    private suspend fun finalize(
        chatId: String,
        assistantId: String,
        textBuf: StringBuilder,
        thinkBuf: StringBuilder,
        status: String,
        errorMsg: String?,
    ) {
        chatRepo.finishAssistantMessage(chatId, assistantId, textBuf.toString(), thinkBuf.toString(), status)
        if (textBuf.isNotEmpty()) conversation.add(ChatMessage(ChatMessage.Role.ASSISTANT, textBuf.toString()))
        streamingFlow.value = null
        _ui.value = _ui.value.copy(busy = false, error = errorMsg)
    }

    fun stop() {
        streamJob?.cancel()
    }

    fun newChat() {
        streamJob?.cancel()
        conversation.clear()
        viewModelScope.launch {
            val chat = chatRepo.createChat("New chat")
            chatIdFlow.value = chat.id
        }
    }

    private suspend fun rebuildConversationFrom(chatId: String) {
        conversation.clear()
        val stored = chatRepo.observeMessages(chatId).first()
        stored.forEach { msg ->
            val text = msg.parts.filter { it.type == PartType.TEXT }.joinToString("") { it.text }
            if (text.isNotEmpty()) {
                val role = if (msg.role == MsgRole.ASSISTANT) ChatMessage.Role.ASSISTANT else ChatMessage.Role.USER
                conversation.add(ChatMessage(role, text))
            }
        }
    }

    private fun StreamingMessage.toUiMessage(): UiMessage {
        val parts = buildList {
            if (thinking.isNotEmpty()) add(UiPart(PartType.THINKING, thinking))
            if (text.isNotEmpty()) add(UiPart(PartType.TEXT, text))
        }
        return UiMessage(messageId, MsgRole.ASSISTANT, MsgStatus.STREAMING, parts, System.currentTimeMillis())
    }
}
