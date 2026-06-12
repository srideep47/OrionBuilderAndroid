package com.legion.orion.feature.engine

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.core.datastore.SettingsRepository
import com.legion.orion.engine.mnn.MnnBackend
import com.legion.orion.inference.InferenceController
import com.legion.orion.inference.InferenceService
import com.legion.orion.server.ServerController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EngineUiState(
    val modelDir: String = "",
    val backend: MnnBackend = MnnBackend.CPU,
    val prompt: String = "Explain what a tensor is in one sentence.",
    val output: String = "",
    val tokenCount: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val busy: Boolean = false,
)

@HiltViewModel
class EngineViewModel @Inject constructor(
    application: Application,
    private val controller: InferenceController,
    private val settings: SettingsRepository,
    private val serverController: ServerController,
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(EngineUiState())
    val ui = _ui.asStateFlow()

    val serverRunning = serverController.running

    fun toggleServer(enable: Boolean) {
        if (enable) serverController.start() else serverController.stop()
    }

    fun serverPort(): Int? = serverController.port()

    val engineState = controller.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), controller.state.value,
    )

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

    fun load() {
        val s = _ui.value
        if (s.modelDir.isBlank()) return
        viewModelScope.launch {
            _ui.value = s.copy(busy = true, output = "")
            InferenceService.start(getApplication())
            runCatching { controller.load(s.modelDir, s.backend) }
                .onSuccess { settings.setLastModel(s.modelDir, s.backend.configValue) }
            _ui.value = _ui.value.copy(busy = false)
        }
    }

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
}
