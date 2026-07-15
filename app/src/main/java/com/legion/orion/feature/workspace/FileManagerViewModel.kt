package com.legion.orion.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.core.database.ProjectEntity
import com.legion.orion.data.project.ActiveProjectStore
import com.legion.orion.data.project.ProjectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

data class FileManagerUiState(
    val projectId: String? = null,
    val projectName: String? = null,
    /** Path segments from the project root to the current directory, for the breadcrumb bar. */
    val breadcrumb: List<String> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    val selection: Set<String> = emptySet(),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val hasClipboard: Boolean = false,
)

data class FileProperties(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val itemCount: Int,
    val lastModified: Long,
)

private data class ClipboardOp(val paths: Set<String>, val cut: Boolean)

/**
 * A Finder-style file manager over the real on-disk workspace: project list at the top level, then a
 * real file/folder browser inside each project (`ProjectManager.dirFor(id)`), with rename/delete/new
 * folder/new file and multi-select batch delete. Deliberately scoped to one project's tree at a time —
 * cross-project move/drag is not implemented in this first version.
 */
@HiltViewModel
class FileManagerViewModel @Inject constructor(
    private val projectManager: ProjectManager,
    private val activeProject: ActiveProjectStore,
) : ViewModel() {

    val projects: StateFlow<List<ProjectEntity>> = projectManager.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(FileManagerUiState())
    val ui = _ui.asStateFlow()

    private var projectRoot: File? = null
    private var currentDir: File? = null
    private var clipboard: ClipboardOp? = null

    /** Creates a genuinely blank project (no scaffold template) and opens it — the file manager's
     * own "New Project" entry point, independent of Chat/Claude Code's auto-created projects. */
    fun createProject(name: String) {
        val clean = name.trim().ifBlank { "New Project" }
        viewModelScope.launch {
            val project = projectManager.createBlankProject(clean)
            openProject(project)
        }
    }

    /** Path of the currently open directory, relative to the project root ("" at the root) — what a
     * terminal's `cd` needs, and what [WorkspaceViewModel.openTerminalAt] takes. */
    fun currentRelativePath(): String = _ui.value.breadcrumb.joinToString("/")

    /** Same, but for a specific (directory) entry rather than the currently open folder. */
    fun relativePathFor(entry: FileEntry): String = (_ui.value.breadcrumb + entry.name).joinToString("/")

    fun copySelected() = copyPaths(_ui.value.selection)
    fun cutSelected() = cutPaths(_ui.value.selection)

    /** Copies a single context-menu entry — deliberately independent of [FileManagerUiState.selection]
     * so invoking it on an entry doesn't toggle that entry's multi-select state (which [toggleSelect]
     * would do if reused here, corrupting an in-progress multi-select). */
    fun copyEntry(entry: FileEntry) = copyPaths(setOf(entry.path))
    fun cutEntry(entry: FileEntry) = cutPaths(setOf(entry.path))

    fun deleteEntry(entry: FileEntry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { deleteEntry(File(entry.path)) }
            _ui.value = _ui.value.copy(notice = "Deleted \"${entry.name}\"")
            refresh()
        }
    }

    private fun copyPaths(paths: Set<String>) {
        if (paths.isEmpty()) return
        clipboard = ClipboardOp(paths, cut = false)
        _ui.value = _ui.value.copy(selection = emptySet(), hasClipboard = true, notice = "${paths.size} item(s) copied")
    }

    private fun cutPaths(paths: Set<String>) {
        if (paths.isEmpty()) return
        clipboard = ClipboardOp(paths, cut = true)
        _ui.value = _ui.value.copy(selection = emptySet(), hasClipboard = true, notice = "${paths.size} item(s) cut")
    }

    fun pasteClipboard() {
        val op = clipboard ?: return
        val dir = currentDir ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                op.paths.forEach { path ->
                    val src = File(path)
                    if (!src.exists()) return@forEach
                    val dest = uniqueDestination(dir, src.name)
                    if (op.cut) {
                        if (!src.renameTo(dest)) {
                            src.copyRecursively(dest, overwrite = false)
                            deleteEntry(src)
                        }
                    } else {
                        src.copyRecursively(dest, overwrite = false)
                    }
                }
            }
            if (op.cut) clipboard = null
            _ui.value = _ui.value.copy(hasClipboard = clipboard != null, notice = "Pasted")
            refresh()
        }
    }

    private fun uniqueDestination(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isNotEmpty()) ".$it" else "" }
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    /** Size (recursive for a folder), item count, and dates for the Properties sheet. */
    suspend fun propertiesFor(entry: FileEntry): FileProperties = withContext(Dispatchers.IO) {
        val file = File(entry.path)
        if (file.isDirectory) {
            var size = 0L
            var count = 0
            file.walkTopDown().forEach { f ->
                if (f == file) return@forEach
                count++
                if (f.isFile) size += f.length()
            }
            FileProperties(entry.name, entry.path, true, size, count, file.lastModified())
        } else {
            FileProperties(entry.name, entry.path, false, file.length(), 1, file.lastModified())
        }
    }

    fun openProject(project: ProjectEntity) {
        val root = projectManager.dirFor(project.id)
        projectRoot = root
        currentDir = root
        activeProject.set(project.id)
        _ui.value = FileManagerUiState(projectId = project.id, projectName = project.name, breadcrumb = emptyList(), hasClipboard = clipboard != null)
        refresh()
    }

    fun closeProject() {
        projectRoot = null
        currentDir = null
        _ui.value = FileManagerUiState(hasClipboard = clipboard != null)
    }

    fun navigateInto(entry: FileEntry) {
        if (!entry.isDirectory) return
        val root = projectRoot ?: return
        val target = File(entry.path)
        if (!isInside(root, target)) {
            _ui.value = _ui.value.copy(error = "Refusing to open \"${entry.name}\" — it points outside the project")
            return
        }
        currentDir = target
        _ui.value = _ui.value.copy(breadcrumb = relativeSegments(root, target), selection = emptySet())
        refresh()
    }

    fun navigateToBreadcrumb(index: Int) {
        val root = projectRoot ?: return
        val segments = _ui.value.breadcrumb.take(index + 1)
        val target = segments.fold(root) { acc, seg -> File(acc, seg) }
        if (!isInside(root, target)) {
            _ui.value = _ui.value.copy(error = "Refusing to open a path outside the project")
            return
        }
        currentDir = target
        _ui.value = _ui.value.copy(breadcrumb = segments, selection = emptySet())
        refresh()
    }

    fun navigateToRoot() {
        val root = projectRoot ?: return
        currentDir = root
        _ui.value = _ui.value.copy(breadcrumb = emptyList(), selection = emptySet())
        refresh()
    }

    fun refresh() {
        val dir = currentDir ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val entries = withContext(Dispatchers.IO) {
                dir.listFiles()
                    ?.filter { it.name != ".git" }
                    ?.map {
                        FileEntry(
                            name = it.name,
                            path = it.absolutePath,
                            isDirectory = it.isDirectory,
                            sizeBytes = if (it.isFile) it.length() else 0L,
                            lastModified = it.lastModified(),
                        )
                    }
                    ?.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
                    ?: emptyList()
            }
            _ui.value = _ui.value.copy(busy = false, entries = entries)
        }
    }

    fun toggleSelect(path: String) {
        val sel = _ui.value.selection
        _ui.value = _ui.value.copy(selection = if (path in sel) sel - path else sel + path)
    }

    fun clearSelection() {
        _ui.value = _ui.value.copy(selection = emptySet())
    }

    fun createFolder(name: String) {
        val dir = currentDir ?: return
        val clean = name.trim().takeIf { it.isNotBlank() && !it.contains('/') } ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { File(dir, clean).mkdirs() }
            refresh()
        }
    }

    fun createFile(name: String) {
        val dir = currentDir ?: return
        val clean = name.trim().takeIf { it.isNotBlank() && !it.contains('/') } ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { File(dir, clean).createNewFile() }.getOrDefault(false) }
            if (!ok) _ui.value = _ui.value.copy(error = "\"$clean\" already exists")
            refresh()
        }
    }

    fun rename(entry: FileEntry, newName: String) {
        val clean = newName.trim().takeIf { it.isNotBlank() && !it.contains('/') } ?: return
        viewModelScope.launch {
            val src = File(entry.path)
            val dst = File(src.parentFile, clean)
            val ok = withContext(Dispatchers.IO) { src.renameTo(dst) }
            if (!ok) _ui.value = _ui.value.copy(error = "Rename failed")
            refresh()
        }
    }

    fun deleteSelected() {
        val sel = _ui.value.selection
        if (sel.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                sel.forEach { path -> deleteEntry(File(path)) }
            }
            _ui.value = _ui.value.copy(selection = emptySet(), notice = "Deleted ${sel.size} item(s)")
            refresh()
        }
    }

    /** Deletes [file] without ever recursing through a symlink into whatever it points at — a
     * symlinked directory entry (e.g. planted by a cloned repo) reports `isDirectory() == true` and
     * would otherwise cause [File.deleteRecursively] to wipe out the link's target instead of just
     * removing the link itself. */
    private fun deleteEntry(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            file.delete()
        } else {
            file.deleteRecursively()
        }
    }

    /** True if [target]'s real (symlink-resolved) location is [root] itself or a descendant of it —
     * a cloned repo can contain a symlink pointing outside the project directory, and browsing into
     * (or otherwise operating on) it must be refused rather than silently escaping the project jail. */
    private fun isInside(root: File, target: File): Boolean {
        val rootPath = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: return false
        val targetPath = runCatching { target.canonicalFile.toPath() }.getOrNull() ?: return false
        return targetPath == rootPath || targetPath.startsWith(rootPath)
    }

    private fun relativeSegments(root: File, target: File): List<String> {
        val rootPath = root.canonicalFile.toPath()
        val targetPath = runCatching { target.canonicalFile.toPath() }.getOrNull() ?: return emptyList()
        if (targetPath != rootPath && !targetPath.startsWith(rootPath)) return emptyList()
        val relative = rootPath.relativize(targetPath)
        return (0 until relative.nameCount).map { relative.getName(it).toString() }
    }
}
