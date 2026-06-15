package com.legion.orion.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.core.database.ProjectEntity
import com.legion.orion.data.project.ActiveProjectStore
import com.legion.orion.data.project.ProjectManager
import com.legion.orion.data.project.ScaffoldManager
import com.legion.orion.engine.agent.ProjectLocks
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class EditorUiState(
    val projectDir: String? = null,
    val projectName: String? = null,
    val files: List<String> = emptyList(),   // project-relative paths
    val openTabs: List<String> = emptyList(),
    val openPath: String? = null,
    val content: String = "",
    val dirty: Boolean = false,
    val savedAt: Long = 0,
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projects: ProjectManager,
    private val activeProject: ActiveProjectStore,
    val scaffolds: ScaffoldManager,
) : ViewModel() {

    private val _ui = MutableStateFlow(EditorUiState())
    val ui = _ui.asStateFlow()

    val allProjects: StateFlow<List<ProjectEntity>> = projects.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            combine(projects.observeProjects(), activeProject.projectId) { all, activeId ->
                all.firstOrNull { it.id == activeId } ?: all.firstOrNull()
            }.collect { project ->
                if (project == null) {
                    _ui.value = EditorUiState()
                } else if (_ui.value.projectDir != projects.dirFor(project.id).absolutePath) {
                    loadProject(project.id)
                }
            }
        }
    }

    private suspend fun loadProject(projectId: String) {
        val entity = projects.get(projectId)
        val dir = projects.dirFor(projectId)
        val files = listFiles(dir)
        _ui.value = EditorUiState(
            projectDir = dir.absolutePath,
            projectName = entity?.name,
            files = files,
        )
        (files.firstOrNull { it.endsWith(".tsx") || it.endsWith(".html") || it.endsWith(".kt") || it.endsWith(".js") }
            ?: files.firstOrNull())?.let { open(it) }
    }

    fun selectProject(id: String) {
        activeProject.set(id)
        viewModelScope.launch { loadProject(id) }
    }

    fun createProject(name: String, templateId: String) {
        viewModelScope.launch {
            val project = projects.createProject(
                name.ifBlank { "New project" },
                templateId = templateId,
                stack = if (templateId == "android-kt") "android" else "web",
            )
            activeProject.set(project.id)
            loadProject(project.id)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val project = activeProject.projectId.value
                ?: projects.observeProjects().first().firstOrNull()?.id
                ?: return@launch
            val dir = projects.dirFor(project)
            _ui.value = _ui.value.copy(files = listFiles(dir))
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
            .take(600)
            .toList()

    fun open(relPath: String) {
        val dir = _ui.value.projectDir ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(dir, relPath)
            val text = runCatching { file.readText() }.getOrDefault("")
            val tabs = _ui.value.openTabs.let { if (relPath in it) it else it + relPath }
            _ui.value = _ui.value.copy(openPath = relPath, openTabs = tabs, content = text, dirty = false)
        }
    }

    fun closeTab(relPath: String) {
        val st = _ui.value
        val tabs = st.openTabs - relPath
        if (st.openPath == relPath) {
            val next = tabs.lastOrNull()
            if (next != null) {
                _ui.value = st.copy(openTabs = tabs)
                open(next)
            } else {
                _ui.value = st.copy(openTabs = tabs, openPath = null, content = "", dirty = false)
            }
        } else {
            _ui.value = st.copy(openTabs = tabs)
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
