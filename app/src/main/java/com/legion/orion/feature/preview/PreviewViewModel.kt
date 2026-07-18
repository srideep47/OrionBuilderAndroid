package com.legion.orion.feature.preview

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.build.ApkBuildController
import com.legion.orion.build.AndroidProjectLocator
import com.legion.orion.automation.DeviceAutomationController
import com.legion.orion.data.project.ActiveProjectStore
import com.legion.orion.data.project.ProjectManager
import com.legion.orion.runtime.NodeController
import com.legion.orion.runtime.DevServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PreviewPanel { PREVIEW, PROBLEMS, LOGS, A11Y, SECURITY, PUBLISH }

enum class PreviewKind { WEB, ANDROID, GENERIC }

data class PreviewProject(
    val id: String,
    val name: String,
    val path: String,
    val kind: PreviewKind,
    val description: String,
)

@HiltViewModel
class PreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val previewController: PreviewController,
    private val devServerState: DevServerState,
    private val node: NodeController,
    private val projects: ProjectManager,
    private val activeProject: ActiveProjectStore,
    private val apkBuilder: ApkBuildController,
    private val deviceAutomation: DeviceAutomationController,
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
    val deviceVerification = deviceAutomation.connected

    private val _project = MutableStateFlow<PreviewProject?>(null)
    val project = _project.asStateFlow()

    private val _androidBuildLog = MutableStateFlow("")
    val androidBuildLog = _androidBuildLog.asStateFlow()

    private val _androidBuildSucceeded = MutableStateFlow(false)
    val androidBuildSucceeded = _androidBuildSucceeded.asStateFlow()

    init {
        viewModelScope.launch {
            combine(projects.observeProjects(), activeProject.projectId) { all, activeId ->
                all.firstOrNull { it.id == activeId } ?: all.firstOrNull()
            }.collect { entity ->
                _project.value = entity?.let { detectProject(it.id, it.name, projects.dirFor(it.id)) }
                _error.value = null
                _androidBuildSucceeded.value = false
                _androidBuildLog.value = ""
            }
        }
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
            val detected = detectProject(project.id, project.name, projects.dirFor(project.id))
            _project.value = detected
            if (detected.kind != PreviewKind.WEB) {
                _error.value = when (detected.kind) {
                    PreviewKind.ANDROID -> "Android projects run as installed apps. Use Build & install below."
                    PreviewKind.GENERIC -> "This project has no browser entry point. Run its commands from Terminal."
                    PreviewKind.WEB -> null
                }
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

    fun buildAndroid(install: Boolean) {
        val selected = _project.value?.takeIf { it.kind == PreviewKind.ANDROID } ?: return
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            _androidBuildSucceeded.value = false
            _androidBuildLog.value = "Starting on-device Android build…\n"
            val result = runCatching {
                apkBuilder.buildAndInstall(File(selected.path), install) { line ->
                    _androidBuildLog.value = (_androidBuildLog.value + line + "\n").takeLast(80_000)
                }
            }.onFailure { _error.value = it.message ?: "Android build failed" }.getOrNull()
            if (result != null) {
                _androidBuildSucceeded.value = result.success
                if (!result.success) _error.value = "Build failed (exit ${result.exitCode}). See build output."
            }
            _busy.value = false
        }
    }

    fun androidToolchainStatus(): String = apkBuilder.toolchainStatus().describe()

    fun openDeviceVerificationSettings() = deviceAutomation.openAccessibilitySettings()

    fun launchAndroidApp() {
        val selected = _project.value?.takeIf { it.kind == PreviewKind.ANDROID } ?: return
        viewModelScope.launch {
            val packageName = withContext(Dispatchers.IO) {
                AndroidProjectLocator.locate(File(selected.path))?.let(AndroidProjectLocator::applicationId)
            }
            if (packageName == null) {
                _error.value = "Could not determine applicationId from the app module."
                return@launch
            }
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                _error.value = "$packageName is not installed yet. Complete the Android install prompt first."
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
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

    private suspend fun detectProject(id: String, name: String, dir: File): PreviewProject = withContext(Dispatchers.IO) {
        val android = AndroidProjectLocator.locate(dir)
        val webEntry = sequenceOf(
            File(dir, "index.html"),
            File(dir, "public/index.html"),
            File(dir, "dist/index.html"),
            File(dir, "build/index.html"),
        ).firstOrNull { it.isFile }
        when {
            android != null -> PreviewProject(
                id,
                name,
                dir.absolutePath,
                PreviewKind.ANDROID,
                "Android application · ${android.relativeGradleRoot} · ${android.assembleDebugTask}",
            )
            webEntry != null -> PreviewProject(id, name, dir.absolutePath, PreviewKind.WEB, "Browser project · ${webEntry.relativeTo(dir).path.replace('\\', '/')}")
            else -> PreviewProject(id, name, dir.absolutePath, PreviewKind.GENERIC, "No visual entry point detected · use Editor and Terminal")
        }
    }

}
