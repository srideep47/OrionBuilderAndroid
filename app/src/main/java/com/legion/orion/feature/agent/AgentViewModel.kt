package com.legion.orion.feature.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.core.database.ProviderEntity
import com.legion.orion.data.project.ProjectManager
import com.legion.orion.data.providers.ProviderRepository
import com.legion.orion.data.providers.ProviderResolver
import com.legion.orion.data.providers.ResolveResult
import com.legion.orion.engine.agent.AgentConfig
import com.legion.orion.engine.agent.AgentEvent
import com.legion.orion.engine.agent.AgentLoop
import com.legion.orion.engine.agent.SnapshotRef
import com.legion.orion.engine.agent.ToolRegistry
import com.legion.orion.engine.agent.tools.DefaultTools
import com.legion.orion.engine.agent.SystemPrompts
import com.legion.orion.agent.AppAgentTools
import com.legion.orion.engine.providers.ChatMessage
import com.legion.orion.infra.git.GitSnapshotter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** UI transcript items for an agent run. */
sealed interface AgentItem {
    data class Assistant(val text: String) : AgentItem
    data class Thinking(val text: String) : AgentItem
    data class Tool(
        val id: String,
        val name: String,
        val args: String,
        val status: String,        // running | done | error
        val result: String? = null,
        val snapshot: SnapshotRef? = null,
    ) : AgentItem
    data class Note(val text: String) : AgentItem
}

data class AgentUiState(
    val providers: List<ProviderEntity> = emptyList(),
    val selectedProvider: ProviderEntity? = null,
    val model: String = "",
    val projectName: String? = null,
    val task: String = "Add a dark-mode toggle button to the page.",
    val items: List<AgentItem> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AgentViewModel @Inject constructor(
    private val projects: ProjectManager,
    private val providerRepo: ProviderRepository,
    private val resolver: ProviderResolver,
    private val appAgentTools: AppAgentTools,
) : ViewModel() {

    private val _ui = MutableStateFlow(AgentUiState())
    val ui = _ui.asStateFlow()

    private val registry = ToolRegistry(DefaultTools.list() + appAgentTools.list())
    private var projectId: String? = null
    private var runJob: Job? = null

    init {
        viewModelScope.launch {
            providerRepo.seedDefaultsIfEmpty()
            val provs = providerRepo.observeProviders().first()
            val chosen = provs.firstOrNull()
            val model = chosen?.let { providerRepo.modelsFor(it.id).firstOrNull()?.modelId } ?: ""
            _ui.value = _ui.value.copy(providers = provs, selectedProvider = chosen, model = model)
            ensureProject()
        }
    }

    fun onTask(text: String) { _ui.value = _ui.value.copy(task = text) }

    fun chooseProvider(p: ProviderEntity) {
        viewModelScope.launch {
            val model = providerRepo.modelsFor(p.id).firstOrNull()?.modelId ?: ""
            _ui.value = _ui.value.copy(selectedProvider = p, model = model)
        }
    }

    private suspend fun ensureProject() {
        val existing = projects.observeProjects().first().firstOrNull()
        val project = existing ?: projects.createProject("Playground")
        projectId = project.id
        _ui.value = _ui.value.copy(projectName = project.name)
    }

    fun run() {
        val state = _ui.value
        val provider = state.selectedProvider ?: run {
            _ui.value = state.copy(error = "Select a provider"); return
        }
        if (state.task.isBlank() || state.running) return

        runJob?.cancel()
        runJob = viewModelScope.launch {
            _ui.value = state.copy(running = true, error = null, items = emptyList())
            val pid = projectId ?: projects.createProject("Playground").also { projectId = it.id }.id
            val dir = projects.dirFor(pid)

            val resolved = when (val r = resolver.resolve(provider)) {
                is ResolveResult.MissingKey -> { fail("Add an API key for ${provider.label} in Settings"); return@launch }
                is ResolveResult.Misconfigured -> { fail(r.reason); return@launch }
                is ResolveResult.Ready -> r.provider
            }

            val window = providerRepo.modelsFor(provider.id).firstOrNull()?.contextWindow ?: 32_768
            val loop = AgentLoop(
                provider = resolved,
                registry = registry,
                snapshotter = GitSnapshotter(dir),
                config = AgentConfig(model = state.model, contextWindowTokens = window),
                projectDir = dir,
                systemPrompt = SystemPrompts.full(),
            )

            val history = listOf(ChatMessage(ChatMessage.Role.USER, state.task))
            try {
                loop.run(chatId = pid, history = history).collect { ev -> reduce(ev) }
            } catch (t: Throwable) {
                fail(t.message ?: "agent run failed"); return@launch
            }
            _ui.value = _ui.value.copy(running = false)
        }
    }

    fun stop() { runJob?.cancel(); _ui.value = _ui.value.copy(running = false) }

    fun rollback(ref: SnapshotRef) {
        val pid = projectId ?: return
        viewModelScope.launch {
            runCatching { GitSnapshotter(projects.dirFor(pid)).restore(ref) }
                .onSuccess { addItem(AgentItem.Note("Rolled back to snapshot ${ref.commit.take(7)}")) }
                .onFailure { addItem(AgentItem.Note("Rollback failed: ${it.message}")) }
        }
    }

    // ── event reduction ──

    private fun reduce(ev: AgentEvent) {
        when (ev) {
            is AgentEvent.TextDelta -> appendAssistant(ev.text)
            is AgentEvent.ThinkingDelta -> appendThinking(ev.text)
            is AgentEvent.AssistantMessage -> {} // already streamed via deltas
            is AgentEvent.ToolStarted -> addItem(AgentItem.Tool(ev.id, ev.name, ev.argsJson, "running"))
            is AgentEvent.ToolFinished -> updateTool(ev.id) {
                it.copy(
                    status = if (ev.result.isError) "error" else "done",
                    result = ev.result.content,
                    snapshot = ev.snapshot,
                )
            }
            is AgentEvent.Compacted -> addItem(AgentItem.Note("Context compacted (${ev.removedMessages} messages)"))
            AgentEvent.TurnComplete -> _ui.value = _ui.value.copy(running = false)
            is AgentEvent.Failed -> fail(ev.message)
        }
    }

    private fun appendAssistant(text: String) {
        val items = _ui.value.items.toMutableList()
        val last = items.lastOrNull()
        if (last is AgentItem.Assistant) items[items.lastIndex] = last.copy(text = last.text + text)
        else items.add(AgentItem.Assistant(text))
        _ui.value = _ui.value.copy(items = items)
    }

    private fun appendThinking(text: String) {
        val items = _ui.value.items.toMutableList()
        val last = items.lastOrNull()
        if (last is AgentItem.Thinking) items[items.lastIndex] = last.copy(text = last.text + text)
        else items.add(AgentItem.Thinking(text))
        _ui.value = _ui.value.copy(items = items)
    }

    private fun addItem(item: AgentItem) {
        _ui.value = _ui.value.copy(items = _ui.value.items + item)
    }

    private fun updateTool(id: String, transform: (AgentItem.Tool) -> AgentItem.Tool) {
        val items = _ui.value.items.toMutableList()
        val idx = items.indexOfLast { it is AgentItem.Tool && it.id == id }
        if (idx >= 0) items[idx] = transform(items[idx] as AgentItem.Tool)
        _ui.value = _ui.value.copy(items = items)
    }

    private fun fail(message: String) {
        _ui.value = _ui.value.copy(running = false, error = message)
    }
}
