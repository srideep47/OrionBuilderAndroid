package com.legion.orion.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.data.project.ActiveProjectStore
import com.legion.orion.data.project.ProjectManager
import com.legion.orion.engine.agent.ProjectLocks
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class EditorUiState(
    val projectDir: String? = null,
    val files: List<String> = emptyList(),   // project-relative paths
    val openPath: String? = null,
    val content: String = "",
    val dirty: Boolean = false,
    val savedAt: Long = 0,
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projects: ProjectManager,
    private val activeProject: ActiveProjectStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(EditorUiState())
    val ui = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            combine(projects.observeProjects(), activeProject.projectId) { all, activeId ->
                all.firstOrNull { it.id == activeId } ?: all.firstOrNull()
            }.collect { project ->
                if (project == null) {
                    _ui.value = EditorUiState()
                } else {
                    loadProject(project.id)
                }
            }
        }
    }

    private suspend fun loadProject(projectId: String) {
        val dir = projects.dirFor(projectId)
        val files = listFiles(dir)
        val previous = _ui.value.openPath?.takeIf { it in files }
        _ui.value = _ui.value.copy(projectDir = dir.absolutePath, files = files, openPath = null, content = "", dirty = false)
        (previous ?: files.firstOrNull { it.endsWith(".tsx") || it.endsWith(".html") || it.endsWith(".js") } ?: files.firstOrNull())
            ?.let { open(it) }
    }

    fun refresh() {
        viewModelScope.launch {
            val project = activeProject.projectId.value
                ?: projects.observeProjects().first().firstOrNull()?.id
                ?: return@launch
            loadProject(project)
        }
    }

    private fun listFiles(dir: File): List<String> =
        dir.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.path.contains("${File.separator}node_modules${File.separator}") }
            .filterNot { it.path.contains("${File.separator}.git${File.separator}") }
            .filterNot { it.path.contains("${File.separator}.orion${File.separator}") }
            .map { it.relativeTo(dir).path.replace('\\', '/') }
            .sorted()
            .take(300)
            .toList()

    fun open(relPath: String) {
        val dir = _ui.value.projectDir ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(dir, relPath)
            val text = runCatching { file.readText() }.getOrDefault("")
            _ui.value = _ui.value.copy(openPath = relPath, content = text, dirty = false)
        }
    }

    fun onContentChange(text: String) {
        if (text != _ui.value.content) _ui.value = _ui.value.copy(content = text, dirty = true)
    }

    fun save() {
        val dir = _ui.value.projectDir ?: return
        val rel = _ui.value.openPath ?: return
        viewModelScope.launch {
            // Share the project lock with the agent so concurrent writes serialize.
            ProjectLocks.forPath(File(dir).canonicalPath).withLock {
                withContext(Dispatchers.IO) { File(dir, rel).writeText(_ui.value.content) }
            }
            _ui.value = _ui.value.copy(dirty = false, savedAt = System.currentTimeMillis())
        }
    }
}
