package com.legion.orion.feature.workspace

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.core.database.ProjectEntity
import com.legion.orion.data.project.ActiveProjectStore
import com.legion.orion.data.project.ProjectManager
import com.legion.orion.util.uniqueDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    val query: String = "",
    val showHidden: Boolean = false,
    val sort: FileSort = FileSort.NAME,
    val ascending: Boolean = true,
    val searchBusy: Boolean = false,
    val searchResults: List<ProjectSearchResult> = emptyList(),
)

enum class FileSort(val label: String) { NAME("Name"), MODIFIED("Modified"), SIZE("Size"), TYPE("Type") }

data class ProjectSearchResult(
    val path: String,
    val line: Int?,
    val preview: String,
)

data class FileProperties(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val itemCount: Int,
    val lastModified: Long,
)

private sealed interface ClipboardOp {
    val cut: Boolean

    data class Files(val paths: Set<String>, override val cut: Boolean) : ClipboardOp
    data class Projects(val ids: Set<String>, override val cut: Boolean) : ClipboardOp
}

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
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val projects: StateFlow<List<ProjectEntity>> = projectManager.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(FileManagerUiState())
    val ui = _ui.asStateFlow()

    private var projectRoot: File? = null
    private var currentDir: File? = null
    private var clipboard: ClipboardOp? = null

    init {
        viewModelScope.launch {
            activeProject.projectId.collectLatest { id ->
                if (id != null && _ui.value.projectId == null) {
                    projectManager.get(id)?.let(::openProject)
                }
            }
        }
    }

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

    fun navigateUp() {
        val crumbs = _ui.value.breadcrumb
        if (crumbs.isEmpty()) return
        if (crumbs.size == 1) navigateToRoot() else navigateToBreadcrumb(crumbs.lastIndex - 1)
    }

    fun setQuery(value: String) {
        _ui.value = _ui.value.copy(query = value)
        if (value.isBlank()) _ui.value = _ui.value.copy(searchBusy = false, searchResults = emptyList())
    }

    fun toggleHidden() {
        _ui.value = _ui.value.copy(showHidden = !_ui.value.showHidden)
        refresh()
    }

    fun setSort(sort: FileSort) {
        val state = _ui.value
        _ui.value = state.copy(
            sort = sort,
            ascending = if (state.sort == sort) !state.ascending else sort == FileSort.NAME,
        )
        refresh()
    }

    fun selectAll() {
        _ui.value = _ui.value.copy(selection = _ui.value.entries.mapTo(mutableSetOf()) { it.path })
    }

    fun copySelected() = copyPaths(_ui.value.selection)
    fun cutSelected() = cutPaths(_ui.value.selection)

    fun copyProject(project: ProjectEntity) {
        clipboard = ClipboardOp.Projects(setOf(project.id), cut = false)
        _ui.value = _ui.value.copy(hasClipboard = true, notice = "Copied project \"${project.name}\"")
    }

    fun cutProject(project: ProjectEntity) {
        clipboard = ClipboardOp.Projects(setOf(project.id), cut = true)
        _ui.value = _ui.value.copy(
            hasClipboard = true,
            notice = "Cut \"${project.name}\". Open another project and Paste to move it there.",
        )
    }

    fun duplicateProject(project: ProjectEntity) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null)
            val result = runCatching { projectManager.duplicateProject(project.id) }
            _ui.value = _ui.value.copy(
                busy = false,
                error = result.exceptionOrNull()?.message,
                notice = result.getOrNull()?.let { "Created \"${it.name}\"" },
            )
        }
    }

    fun renameProject(project: ProjectEntity, name: String) {
        val clean = name.trim().takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            projectManager.rename(project.id, clean)
            _ui.value = _ui.value.copy(notice = "Renamed project to \"$clean\"")
        }
    }

    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null)
            val wasOpen = _ui.value.projectId == project.id
            val result = runCatching { projectManager.deleteProject(project.id) }
            if (result.isSuccess) {
                if (wasOpen) closeProject()
                if (activeProject.projectId.value == project.id) {
                    activeProject.set(projects.value.firstOrNull { it.id != project.id }?.id)
                }
            }
            _ui.value = _ui.value.copy(
                busy = false,
                error = result.exceptionOrNull()?.message,
                notice = if (result.isSuccess) "Deleted project \"${project.name}\"" else null,
            )
        }
    }

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

    fun duplicateEntry(entry: FileEntry) {
        val dir = currentDir ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val src = File(entry.path)
                    require(isInside(projectRoot ?: dir, src)) { "Path is outside this project" }
                    src.copyRecursively(uniqueDestination(dir, src.name), overwrite = false)
                }
            }
            _ui.value = _ui.value.copy(
                error = result.exceptionOrNull()?.message,
                notice = if (result.isSuccess) "Duplicated \"${entry.name}\"" else null,
            )
            refresh()
        }
    }

    fun compressEntry(entry: FileEntry) = compressPaths(setOf(entry.path))
    fun compressSelected() = compressPaths(_ui.value.selection)

    private fun compressPaths(paths: Set<String>) {
        val dir = currentDir ?: return
        val root = projectRoot ?: return
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val suggested = if (paths.size == 1) File(paths.first()).nameWithoutExtension else "archive"
                    val output = uniqueDestination(dir, "$suggested.zip")
                    ZipOutputStream(FileOutputStream(output)).use { zip ->
                        paths.map(::File).forEach { source ->
                            require(isInside(root, source)) { "Path is outside this project" }
                            addToZip(zip, source, source.name)
                        }
                    }
                    output.name
                }
            }
            _ui.value = _ui.value.copy(
                busy = false,
                selection = emptySet(),
                error = result.exceptionOrNull()?.message,
                notice = result.getOrNull()?.let { "Created $it" },
            )
            refresh()
        }
    }

    fun extractZip(entry: FileEntry) {
        val dir = currentDir ?: return
        val root = projectRoot ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val archive = File(entry.path)
                    require(archive.extension.equals("zip", true)) { "Only ZIP archives can be extracted" }
                    val destination = uniqueDestination(dir, archive.nameWithoutExtension).apply { mkdirs() }
                    val destinationPath = destination.canonicalFile.toPath()
                    ZipInputStream(FileInputStream(archive)).use { zip ->
                        var item = zip.nextEntry
                        while (item != null) {
                            val output = File(destination, item.name)
                            val outputPath = output.canonicalFile.toPath()
                            require(outputPath == destinationPath || outputPath.startsWith(destinationPath)) {
                                "Unsafe path in archive: ${item.name}"
                            }
                            require(isInside(root, output)) { "Archive would write outside this project" }
                            if (item.isDirectory) output.mkdirs() else {
                                output.parentFile?.mkdirs()
                                FileOutputStream(output).use { zip.copyTo(it) }
                            }
                            zip.closeEntry()
                            item = zip.nextEntry
                        }
                    }
                    destination.name
                }
            }
            _ui.value = _ui.value.copy(
                busy = false,
                error = result.exceptionOrNull()?.message,
                notice = result.getOrNull()?.let { "Extracted to $it" },
            )
            refresh()
        }
    }

    private fun copyPaths(paths: Set<String>) {
        if (paths.isEmpty()) return
        clipboard = ClipboardOp.Files(paths, cut = false)
        _ui.value = _ui.value.copy(selection = emptySet(), hasClipboard = true, notice = "${paths.size} item(s) copied")
    }

    private fun cutPaths(paths: Set<String>) {
        if (paths.isEmpty()) return
        clipboard = ClipboardOp.Files(paths, cut = true)
        _ui.value = _ui.value.copy(selection = emptySet(), hasClipboard = true, notice = "${paths.size} item(s) cut")
    }

    fun pasteClipboard() {
        val op = clipboard ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null)
            val result = runCatching {
                when (op) {
                    is ClipboardOp.Files -> {
                        val dir = currentDir ?: error("Open a project folder before pasting files")
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
                    }
                    is ClipboardOp.Projects -> pasteProjects(op)
                }
            }
            if (result.isSuccess && op.cut) clipboard = null
            _ui.value = _ui.value.copy(
                busy = false,
                hasClipboard = clipboard != null,
                error = result.exceptionOrNull()?.message,
                notice = if (result.isSuccess) "Pasted" else null,
            )
            refresh()
        }
    }

    private suspend fun pasteProjects(op: ClipboardOp.Projects) {
        val destinationProjectId = _ui.value.projectId
        if (destinationProjectId == null) {
            require(!op.cut) { "Open a destination project and Paste to move the cut project folder" }
            op.ids.forEach { projectManager.duplicateProject(it) }
            return
        }
        val relativeDestination = currentRelativePath()
        op.ids.forEach { sourceId ->
            projectManager.transferProjectInto(
                sourceId = sourceId,
                destinationProjectId = destinationProjectId,
                destinationRelativePath = relativeDestination,
                move = op.cut,
            )
        }
        if (op.cut && activeProject.projectId.value in op.ids) activeProject.set(destinationProjectId)
    }

    /** Copies each picked SAF document into the currently open folder (not an auto-categorized
     * subfolder — matches every other action here, which operates on [currentDir]). Real display
     * names are queried separately since `content://` URIs don't carry a filename directly. */
    fun importFiles(uris: List<Uri>) {
        val dir = currentDir ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var count = 0
                    uris.forEach { uri ->
                        val name = queryDisplayName(uri) ?: return@forEach
                        val dest = uniqueDestination(dir, name)
                        val opened = context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(dest).use { input.copyTo(it) }
                            true
                        } ?: false
                        if (opened) count++
                    }
                    count
                }
            }
            _ui.value = _ui.value.copy(
                busy = false,
                error = result.exceptionOrNull()?.message,
                notice = result.getOrNull()?.let { "Imported $it file(s)" },
            )
            refresh()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && cursor.moveToFirst()) return cursor.getString(col)
        }
        return uri.lastPathSegment
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
            val state = _ui.value
            val entries = withContext(Dispatchers.IO) {
                dir.listFiles()
                    ?.filter { it.name != ".git" }
                    ?.filter { state.showHidden || !it.name.startsWith('.') }
                    ?.map {
                        FileEntry(
                            name = it.name,
                            path = it.absolutePath,
                            isDirectory = it.isDirectory,
                            sizeBytes = if (it.isFile) it.length() else 0L,
                            lastModified = it.lastModified(),
                        )
                    }
                    ?.sortedWith(entryComparator(state.sort, state.ascending))
                    ?: emptyList()
            }
            _ui.value = _ui.value.copy(busy = false, entries = entries)
        }
    }

    /** Searches names and text across the active project. Expensive/generated trees and binary files
     * are skipped, individual files are capped at 1 MiB, and results are capped to keep the UI fast. */
    fun searchProject(query: String = _ui.value.query) {
        val root = projectRoot ?: return
        val needle = query.trim()
        if (needle.length < 2) {
            _ui.value = _ui.value.copy(searchBusy = false, searchResults = emptyList())
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(searchBusy = true)
            val results = withContext(Dispatchers.IO) {
                val found = mutableListOf<ProjectSearchResult>()
                val ignored = setOf(".git", "node_modules", "build", ".gradle", ".idea", ".orion", "dist")
                root.walkTopDown()
                    .onEnter { it == root || (it.name !in ignored && (_ui.value.showHidden || !it.name.startsWith('.'))) }
                    .filter { it.isFile && it.length() <= 1024L * 1024L }
                    .takeWhile { found.size < 200 }
                    .forEach { file ->
                        val relative = file.relativeTo(root).path.replace('\\', '/')
                        if (relative.contains(needle, ignoreCase = true)) {
                            found += ProjectSearchResult(relative, null, "File name match")
                        }
                        if (found.size >= 200) return@forEach
                        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                        if (bytes.any { it == 0.toByte() }) return@forEach
                        bytes.toString(Charsets.UTF_8).lineSequence().forEachIndexed { index, line ->
                            if (found.size < 200 && line.contains(needle, ignoreCase = true)) {
                                found += ProjectSearchResult(relative, index + 1, line.trim().take(180))
                            }
                        }
                    }
                found.distinctBy { Triple(it.path, it.line, it.preview) }
            }
            if (_ui.value.query.trim() == needle) {
                _ui.value = _ui.value.copy(searchBusy = false, searchResults = results)
            }
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

    private fun entryComparator(sort: FileSort, ascending: Boolean): Comparator<FileEntry> {
        val valueComparator = when (sort) {
            FileSort.NAME -> compareBy<FileEntry> { it.name.lowercase(Locale.ROOT) }
            FileSort.MODIFIED -> compareBy { it.lastModified }
            FileSort.SIZE -> compareBy { it.sizeBytes }
            FileSort.TYPE -> compareBy<FileEntry> { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        }
        val ordered = if (ascending) valueComparator else valueComparator.reversed()
        return compareByDescending<FileEntry> { it.isDirectory }.then(ordered)
    }

    private fun addToZip(zip: ZipOutputStream, file: File, entryName: String) {
        if (Files.isSymbolicLink(file.toPath())) return
        if (file.isDirectory) {
            val children = file.listFiles().orEmpty()
            if (children.isEmpty()) {
                zip.putNextEntry(ZipEntry(entryName.trimEnd('/') + "/"))
                zip.closeEntry()
            } else {
                children.forEach { addToZip(zip, it, "$entryName/${it.name}") }
            }
        } else {
            zip.putNextEntry(ZipEntry(entryName))
            FileInputStream(file).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}
