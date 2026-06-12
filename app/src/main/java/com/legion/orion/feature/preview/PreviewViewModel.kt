package com.legion.orion.feature.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.data.project.ActiveProjectStore
import com.legion.orion.data.project.ProjectManager
import com.legion.orion.runtime.NodeController
import com.legion.orion.runtime.DevServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class PreviewPanel { PREVIEW, PROBLEMS, LOGS, A11Y, SECURITY, PUBLISH }

@HiltViewModel
class PreviewViewModel @Inject constructor(
    val previewController: PreviewController,
    private val devServerState: DevServerState,
    private val node: NodeController,
    private val projects: ProjectManager,
    private val activeProject: ActiveProjectStore,
) : ViewModel() {
    val devServer = devServerState.current

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _fullscreen = MutableStateFlow(false)
    val fullscreen = _fullscreen.asStateFlow()

    private val _panel = MutableStateFlow(PreviewPanel.PREVIEW)
    val panel = _panel.asStateFlow()

    private val _runtimeLogs = MutableStateFlow("")
    val runtimeLogs = _runtimeLogs.asStateFlow()

    private val _a11yTree = MutableStateFlow<String?>(null)
    val a11yTree = _a11yTree.asStateFlow()

    val browserState = previewController.browserState

    init {
        viewModelScope.launch {
            devServer.collect { running ->
                if (running == null) {
                    _runtimeLogs.value = ""
                } else {
                    while (devServer.value?.port == running.port) {
                        refreshLogs()
                        delay(1200)
                    }
                }
            }
        }
    }

    fun setPanel(panel: PreviewPanel) {
        _panel.value = panel
        if (panel == PreviewPanel.A11Y) refreshAccessibility()
    }

    fun toggleFullscreen() { _fullscreen.value = !_fullscreen.value }

    fun startPreview() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            val project = activeProject.projectId.value?.let { projects.get(it) }
                ?: projects.observeProjects().first().firstOrNull()
            if (project == null) {
                _error.value = "Create a project from Chat -> Build first."
                _busy.value = false
                return@launch
            }
            runCatching {
                val port = node.startDevServer(project.path)
                require(port > 0) { "Dev server did not return a port" }
                activeProject.set(project.id)
                devServerState.set(DevServerState.Running(port, project.path))
            }.onFailure {
                _error.value = it.message ?: "Preview failed to start"
            }
            _busy.value = false
        }
    }

    fun stopPreview() {
        val running = devServer.value ?: return
        viewModelScope.launch {
            runCatching { node.stopDevServer(running.port) }
            devServerState.set(null)
            previewController.detach()
        }
    }

    fun restartPreview() {
        val running = devServer.value
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            runCatching { if (running != null) node.stopDevServer(running.port) }
            devServerState.set(null)
            previewController.detach()
            _busy.value = false
            startPreview()
        }
    }

    fun goBack() = previewController.goBack()
    fun goForward() = previewController.goForward()
    fun reload() = previewController.reload()

    fun openPath(path: String) {
        val running = devServer.value ?: return
        val normalized = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            running.url.trimEnd('/') + "/" + path.trimStart('/')
        }
        previewController.loadUrl(normalized)
    }

    fun refreshAccessibility() {
        viewModelScope.launch { _a11yTree.value = previewController.accessibilityTree() }
    }

    fun clearDiagnostics() {
        previewController.clearDiagnostics()
        _runtimeLogs.value = ""
    }

    private suspend fun refreshLogs() {
        val running = devServer.value ?: return
        runCatching { node.devServerLogs(running.port) }
            .onSuccess { _runtimeLogs.value = it }
    }
}
