package com.legion.orion.feature.engine

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.core.database.LocalModelDao
import com.legion.orion.core.database.LocalModelEntity
import com.legion.orion.core.datastore.SettingsRepository
import com.legion.orion.data.project.ActiveProjectStore
import com.legion.orion.data.project.ProjectManager
import com.legion.orion.engine.mnn.MnnBackend
import com.legion.orion.inference.InferenceController
import com.legion.orion.inference.InferenceService
import com.legion.orion.runtime.GodotController
import com.legion.orion.runtime.GodotProcessController
import com.legion.orion.runtime.godot.GodotEditorLaunch
import com.legion.orion.runtime.godot.GodotProjectInstaller
import com.legion.orion.server.ServerController
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** User-tunable MNN engine settings surfaced in the Engine cockpit. */
data class EngineSettings(
    val threadNum: Int = 4,
    val precision: String = "low",       // low | normal | high
    val memory: String = "low",          // low | normal | high
    val power: String = "high",          // low | normal | high — GPTee tuned default
    val useMmap: Boolean = false,
    val useCachedMmap: Boolean = true,
    val kvCacheMmap: Boolean = false,
    val reuseKv: Boolean = false,
    val attentionMode: Int = 8,          // 8 off/default, 9 int8 Q/K, 10 int8 Q/K/V
    val dynamicOption: Int = 0,
    val maxAllTokens: Int = 8192,
    val maxNewTokens: Int = 2048,
    val temperature: Float = 0.6f,
    val topP: Float = 0.95f,
    val topK: Int = 20,
    val minP: Float = 0.05f,
    val tfsZ: Float = 1.0f,
    val typical: Float = 0.95f,
    val penalty: Float = 1.02f,
    val nGram: Int = 8,
    val nGramFactor: Float = 1.02f,
    val sampler: String = "greedy", // greedy | temperature | mixed
    val enableThinking: Boolean = true,
)

data class EngineUiState(
    val modelDir: String = "",
    val backend: MnnBackend = MnnBackend.CPU,
    val prompt: String = "Explain what a tensor is in one sentence.",
    val output: String = "",
    val tokenCount: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val busy: Boolean = false,
    val settings: EngineSettings = EngineSettings(),
)

data class EngineMemoryEstimate(
    val totalRamBytes: Long,
    val freeRamBytes: Long,
    val modelBytes: Long,
    val kvCacheBytes: Long,
    val runtimeReserveBytes: Long,
) {
    val projectedFreeBytes: Long get() = freeRamBytes - modelBytes - kvCacheBytes - runtimeReserveBytes
}

@HiltViewModel
class EngineViewModel @Inject constructor(
    application: Application,
    private val controller: InferenceController,
    private val settings: SettingsRepository,
    private val serverController: ServerController,
    private val godot: GodotProcessController,
    private val godotBridge: GodotController,
    private val projects: ProjectManager,
    private val activeProject: ActiveProjectStore,
    localModelDao: LocalModelDao,
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(EngineUiState())
    val ui = _ui.asStateFlow()

    private val _bridgeStatus = MutableStateFlow("Not checked")
    val bridgeStatus = _bridgeStatus.asStateFlow()

    val localModels = localModelDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val serverRunning = serverController.running

    /** Pick a downloaded model from the library: fills the path and loads it. */
    fun loadLocal(model: LocalModelEntity) {
        _ui.value = _ui.value.copy(modelDir = model.localPath)
        load()
    }

    fun toggleServer(enable: Boolean) {
        if (enable) serverController.start() else serverController.stop()
    }

    fun serverPort(): Int? = serverController.port()

    val engineState = controller.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), controller.state.value,
    )

    val godotState = godot.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), godot.state.value,
    )
    val godotProjectPath = godot.projectPath.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), godot.projectPath.value,
    )
    val godotPresentation = godot.presentation.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), godot.presentation.value,
    )

    /**
     * G1 debug entry point: launches the full-screen Godot host running the bundled default
     * project (runtime/godot's `src/main/assets/`, see [GodotProcessController]'s kdoc for why this
     * can't yet point at an arbitrary project directory), so process isolation/lifecycle/
     * renderer-tier can be exercised on-device ahead of the real per-project `DevTool.GAME` wiring
     * (a later phase).
     */
    fun launchGodot() {
        viewModelScope.launch { godot.startForProject(activeGodotDir(), fullscreen = true) }
    }

    /** Opens the complete Godot editor UI from the same tools engine used for game previews. */
    fun launchGodotEditor() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val projectDir = GodotProjectInstaller.prepare(app, activeGodotDir())
            app.startActivity(GodotEditorLaunch.intentFor(app, projectDir.absolutePath))
        }
    }

    fun expandGodot() {
        val path = godot.projectPath.value ?: return
        viewModelScope.launch { godot.restart(File(path), fullscreen = true) }
    }

    fun returnGodotToChat() {
        val path = godot.projectPath.value ?: return
        viewModelScope.launch { godot.restart(File(path), fullscreen = false) }
    }

    fun stopGodot() {
        viewModelScope.launch { godot.teardown() }
    }

    private suspend fun activeGodotDir(): File {
        val id = activeProject.projectId.value
        if (id != null) return projects.dirFor(id)
        val existingGodot = projects.observeProjects().first().firstOrNull { it.stackType == "godot" }
        val project = existingGodot ?: projects.createProject("Godot Game", "godot", "godot")
        activeProject.set(project.id)
        return projects.dirFor(project.id)
    }

    /**
     * G3 debug entry point: exercises the GDExtension AI-control bridge's v1 read-only ops
     * (ping/scene_tree/perf_snapshot) against the running :godot process, over the loopback NDJSON
     * socket (see [GodotController]). Requires the embedded engine to already be running (either
     * debug card above).
     */
    fun pingBridge() {
        viewModelScope.launch {
            _bridgeStatus.value = "Pinging…"
            val ok = godotBridge.ping()
            if (!ok) {
                _bridgeStatus.value = "Ping failed (is the engine running?)"
                return@launch
            }
            val perf = godotBridge.perfSnapshot()
            val tree = godotBridge.sceneTree()
            val fps = perf?.get("fps")?.toString() ?: "?"
            val rootChildren = tree?.get("children")?.toString()?.let { "yes" } ?: "no"
            _bridgeStatus.value = "pong=true, fps=$fps, scene_tree=$rootChildren"
        }
    }

    init {
        viewModelScope.launch {
            settings.lastModelPath.collect { path ->
                if (!path.isNullOrBlank() && _ui.value.modelDir.isBlank()) {
                    _ui.value = _ui.value.copy(modelDir = path)
                }
            }
        }
    }

    fun onModelDirChange(value: String) { _ui.value = _ui.value.copy(modelDir = value) }
    fun onBackendChange(value: MnnBackend) { _ui.value = _ui.value.copy(backend = value) }
    fun onPromptChange(value: String) { _ui.value = _ui.value.copy(prompt = value) }
    fun onSettingsChange(s: EngineSettings) { _ui.value = _ui.value.copy(settings = s) }

    fun memoryEstimate(): EngineMemoryEstimate {
        val app = getApplication<Application>()
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val state = _ui.value
        val modelBytes = dirSize(File(state.modelDir))
        val kvPerToken = (modelBytes / 4096L).coerceIn(256L * 1024L, 2L * 1024L * 1024L)
        val quantScale = when (state.settings.attentionMode) {
            9 -> 0.55
            10 -> 0.38
            else -> 1.0
        }
        val kvCacheBytes = (kvPerToken * state.settings.maxAllTokens * quantScale).toLong()
        val runtimeReserveBytes = 512L * 1024L * 1024L + state.settings.threadNum * 64L * 1024L * 1024L
        return EngineMemoryEstimate(
            totalRamBytes = mi.totalMem,
            freeRamBytes = mi.availMem,
            modelBytes = modelBytes,
            kvCacheBytes = kvCacheBytes,
            runtimeReserveBytes = runtimeReserveBytes,
        )
    }

    fun load() {
        val s = _ui.value
        if (s.modelDir.isBlank()) return
        viewModelScope.launch {
            _ui.value = s.copy(busy = true, output = "")
            InferenceService.start(getApplication())
            runCatching { controller.load(s.modelDir, s.backend, s.settings.toMnn()) }
                .onSuccess { settings.setLastModel(s.modelDir, s.backend.configValue) }
            _ui.value = _ui.value.copy(busy = false)
        }
    }

    private fun EngineSettings.toMnn() = com.legion.orion.engine.mnn.MnnSettings(
        threadNum = threadNum,
        precision = precision,
        memory = memory,
        power = power,
        useMmap = useMmap,
        useCachedMmap = useCachedMmap,
        kvCacheMmap = kvCacheMmap,
        reuseKv = reuseKv,
        attentionMode = attentionMode,
        dynamicOption = dynamicOption,
        maxAllTokens = maxAllTokens,
        maxNewTokens = maxNewTokens,
        temperature = temperature,
        topP = topP,
        topK = topK,
        minP = minP,
        tfsZ = tfsZ,
        typical = typical,
        penalty = penalty,
        nGram = nGram,
        nGramFactor = nGramFactor,
        samplerType = sampler,
        enableThinking = enableThinking,
    )

    fun generate() {
        val s = _ui.value
        if (!controller.isLoaded || s.busy) return
        viewModelScope.launch {
            _ui.value = s.copy(busy = true, output = "", tokenCount = 0, tokensPerSecond = 0.0)
            // Wall-clock start captured from the first emitted chunk to exclude prefill stall.
            var firstAt = 0L
            var count = 0
            val sb = StringBuilder()
            runCatching {
                controller.generate(s.prompt).collect { chunk ->
                    val now = System.nanoTime()
                    if (firstAt == 0L) firstAt = now
                    sb.append(chunk.text)
                    count++
                    val elapsedSec = (now - firstAt) / 1_000_000_000.0
                    val tps = if (elapsedSec > 0) count / elapsedSec else 0.0
                    _ui.value = _ui.value.copy(output = sb.toString(), tokenCount = count, tokensPerSecond = tps)
                }
            }.onFailure { sb.append("\n\n[error] ${it.message}") ; _ui.value = _ui.value.copy(output = sb.toString()) }
            _ui.value = _ui.value.copy(busy = false)
        }
    }

    fun unload() {
        viewModelScope.launch {
            controller.unload()
            InferenceService.stop(getApplication())
            _ui.value = _ui.value.copy(output = "")
        }
    }

    private fun dirSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.name == "config.json" || it.name == "custom_config.json" }
            .sumOf { it.length() }
    }
}
