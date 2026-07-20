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
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val files: List<String> = emptyList(),
    val openTabs: List<String> = emptyList(),
    val dirtyTabs: Set<String> = emptySet(),
    val openPath: String? = null,
    val content: String = "",
    val dirty: Boolean = false,
    val encoding: String = "UTF-8",
    val lineEnding: String = "LF",
    val fileSize: Long = 0,
    val externalChange: Boolean = false,
    val autoSave: Boolean = false,
    val notice: String? = null,
    val savedAt: Long = 0,
    val documentRevision: Long = 0,
)

private data class DocumentBuffer(
    var content: String,
    var savedContent: String,
    var dirty: Boolean,
    var diskModifiedAt: Long,
    var fileSize: Long,
    val charsetName: String,
    val bom: ByteArray,
    var externalChange: Boolean = false,
) {
    val lineEnding: String
        get() = when {
            "\r\n" in content -> "CRLF"
            '\r' in content -> "CR"
            else -> "LF"
        }
}

private data class DecodedDocument(
    val content: String,
    val charsetName: String,
    val bom: ByteArray,
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projects: ProjectManager,
    private val activeProject: ActiveProjectStore,
    val scaffolds: ScaffoldManager,
) : ViewModel() {

    private val _ui = MutableStateFlow(EditorUiState())
    val ui = _ui.asStateFlow()

    private val documents = linkedMapOf<String, DocumentBuffer>()
    private var openJob: Job? = null
    private var autoSaveJob: Job? = null

    val allProjects: StateFlow<List<ProjectEntity>> = projects.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            combine(projects.observeProjects(), activeProject.projectId) { all, activeId ->
                all.firstOrNull { it.id == activeId } ?: all.firstOrNull()
            }.collect { project ->
                if (project == null) {
                    documents.clear()
                    _ui.value = EditorUiState()
                } else if (_ui.value.projectDir != projects.dirFor(project.id).absolutePath) {
                    loadProject(project.id)
                }
            }
        }
    }

    private suspend fun loadProject(projectId: String) {
        openJob?.cancel()
        autoSaveJob?.cancel()
        documents.clear()
        val entity = projects.get(projectId)
        val dir = projects.dirFor(projectId)
        val files = listFiles(dir)
        _ui.value = EditorUiState(
            projectDir = dir.absolutePath,
            projectName = entity?.name,
            files = files,
            autoSave = _ui.value.autoSave,
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
                stack = when (templateId) {
                    "godot" -> "godot"
                    "android-kt" -> "android"
                    else -> "web"
                },
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
            val files = listFiles(dir)
            checkExternalChanges(dir)
            _ui.value = _ui.value.copy(files = files)
            publishCurrent()
        }
    }

    private fun listFiles(dir: File): List<String> =
        dir.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.path.contains("${File.separator}node_modules${File.separator}") }
            .filterNot { it.path.contains("${File.separator}.git${File.separator}") }
            .filterNot { it.path.contains("${File.separator}.orion${File.separator}") }
            .filterNot { it.name.startsWith(".orion-save-") }
            .map { it.relativeTo(dir).path.replace('\\', '/') }
            .sorted()
            .take(MAX_PROJECT_FILES)
            .toList()

    fun open(relPath: String) {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            val activeId = activeProject.projectId.value
            if (activeId != null && _ui.value.projectDir != projects.dirFor(activeId).absolutePath) {
                loadProject(activeId)
            }
            val dir = _ui.value.projectDir ?: return@launch
            val file = safeFile(dir, relPath) ?: return@launch
            if (!file.isFile) return@launch

            val existing = documents[relPath]
            val document = existing ?: withContext(Dispatchers.IO) {
                runCatching { readDocument(file) }.getOrElse { error ->
                    _ui.value = _ui.value.copy(notice = error.message ?: "Could not open $relPath")
                    return@withContext null
                }
            } ?: return@launch
            documents.putIfAbsent(relPath, document)
            val tabs = _ui.value.openTabs.let { if (relPath in it) it else it + relPath }
            _ui.value = _ui.value.copy(
                openPath = relPath,
                openTabs = tabs,
                notice = null,
                documentRevision = _ui.value.documentRevision + 1,
            )
            publishCurrent()
        }
    }

    /** Returns false when the caller must confirm discarding unsaved changes. */
    fun closeTab(relPath: String, discardDirty: Boolean = false): Boolean {
        if (documents[relPath]?.dirty == true && !discardDirty) return false
        autoSaveJob?.cancel()
        documents.remove(relPath)
        val state = _ui.value
        val tabs = state.openTabs - relPath
        if (state.openPath == relPath) {
            val next = tabs.lastOrNull()
            _ui.value = state.copy(openTabs = tabs, openPath = next, notice = null)
            publishCurrent()
        } else {
            _ui.value = state.copy(openTabs = tabs, dirtyTabs = dirtyPaths())
        }
        return true
    }

    fun onContentChange(text: String) {
        val path = _ui.value.openPath ?: return
        val document = documents[path] ?: return
        if (text == document.content) return
        markEdited(document, text)
    }

    fun onInsert(index: Int, text: CharSequence) {
        val document = _ui.value.openPath?.let(documents::get) ?: return
        val safeIndex = index.coerceIn(0, document.content.length)
        markEdited(document, document.content.substring(0, safeIndex) + text + document.content.substring(safeIndex))
    }

    fun onDelete(startIndex: Int, endIndex: Int) {
        val document = _ui.value.openPath?.let(documents::get) ?: return
        val start = startIndex.coerceIn(0, document.content.length)
        val end = endIndex.coerceIn(start, document.content.length)
        markEdited(document, document.content.removeRange(start, end))
    }

    private fun markEdited(document: DocumentBuffer, content: String) {
        val path = _ui.value.openPath ?: return
        document.content = content
        document.dirty = content != document.savedContent
        publishCurrent()
        if (_ui.value.autoSave && !document.externalChange) {
            autoSaveJob?.cancel()
            autoSaveJob = viewModelScope.launch {
                delay(AUTO_SAVE_DELAY_MS)
                save(path)
            }
        }
    }

    fun setAutoSave(enabled: Boolean) {
        _ui.value = _ui.value.copy(autoSave = enabled)
        if (enabled && _ui.value.dirty && !_ui.value.externalChange) {
            autoSaveJob?.cancel()
            autoSaveJob = viewModelScope.launch {
                delay(AUTO_SAVE_DELAY_MS)
                save()
            }
        }
    }

    fun save(force: Boolean = false) {
        val path = _ui.value.openPath ?: return
        save(path, force)
    }

    private fun save(relPath: String, force: Boolean = false) {
        val dir = _ui.value.projectDir ?: return
        val document = documents[relPath] ?: return
        if (!document.dirty) return
        if (document.externalChange && !force) {
            _ui.value = _ui.value.copy(notice = "This file changed on disk. Reload it or choose Overwrite.")
            return
        }
        viewModelScope.launch {
            val file = safeFile(dir, relPath) ?: return@launch
            val content = document.content
            val saved = runCatching {
                ProjectLocks.forPath(File(dir).canonicalPath).withLock {
                    withContext(Dispatchers.IO) { atomicWrite(file, document, content) }
                }
            }
            saved.onSuccess {
                document.dirty = false
                document.savedContent = content
                document.externalChange = false
                document.diskModifiedAt = file.lastModified()
                document.fileSize = file.length()
                _ui.value = _ui.value.copy(savedAt = System.currentTimeMillis(), notice = null)
                publishCurrent()
            }.onFailure { error ->
                _ui.value = _ui.value.copy(notice = "Save failed: ${error.message}")
            }
        }
    }

    fun saveAll() {
        val dirty = documents.filterValues { it.dirty }.keys.toList()
        dirty.forEach { save(it) }
    }

    fun reloadCurrent(discardChanges: Boolean = false) {
        val dir = _ui.value.projectDir ?: return
        val path = _ui.value.openPath ?: return
        val existing = documents[path] ?: return
        if (existing.dirty && !discardChanges) {
            _ui.value = _ui.value.copy(notice = "Unsaved changes are present. Confirm Reload to discard them.")
            return
        }
        viewModelScope.launch {
            val file = safeFile(dir, path) ?: return@launch
            val loaded = withContext(Dispatchers.IO) { runCatching { readDocument(file) } }
            loaded.onSuccess {
                documents[path] = it
                _ui.value = _ui.value.copy(notice = null, documentRevision = _ui.value.documentRevision + 1)
                publishCurrent()
            }.onFailure { _ui.value = _ui.value.copy(notice = "Reload failed: ${it.message}") }
        }
    }

    fun clearNotice() {
        _ui.value = _ui.value.copy(notice = null)
    }

    fun hasDirtyDocuments(): Boolean = documents.values.any { it.dirty }

    private fun publishCurrent() {
        val state = _ui.value
        val document = state.openPath?.let(documents::get)
        _ui.value = state.copy(
            content = document?.content.orEmpty(),
            dirty = document?.dirty == true,
            dirtyTabs = dirtyPaths(),
            encoding = document?.charsetName ?: "UTF-8",
            lineEnding = document?.lineEnding ?: "LF",
            fileSize = document?.fileSize ?: 0,
            externalChange = document?.externalChange == true,
        )
    }

    private fun dirtyPaths(): Set<String> = documents.filterValues { it.dirty }.keys.toSet()

    private suspend fun checkExternalChanges(dir: File) = withContext(Dispatchers.IO) {
        documents.forEach { (path, document) ->
            val file = safeFile(dir.absolutePath, path) ?: return@forEach
            if (!file.exists()) {
                document.externalChange = true
            } else if (file.lastModified() != document.diskModifiedAt) {
                if (document.dirty) {
                    document.externalChange = true
                } else {
                    runCatching { readDocument(file) }.getOrNull()?.let { documents[path] = it }
                }
            }
        }
    }

    private fun safeFile(rootPath: String, relPath: String): File? {
        val root = File(rootPath).canonicalFile
        val file = runCatching { File(root, relPath).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it != root && it.path.startsWith(root.path + File.separator) }
    }

    private fun readDocument(file: File): DocumentBuffer {
        require(file.length() <= MAX_EDITABLE_BYTES) {
            "${file.name} is ${formatBytes(file.length())}; files over ${formatBytes(MAX_EDITABLE_BYTES)} open through Terminal or an external viewer."
        }
        val bytes = file.readBytes()
        val decoded = decode(bytes)
        return DocumentBuffer(
            content = decoded.content,
            savedContent = decoded.content,
            dirty = false,
            diskModifiedAt = file.lastModified(),
            fileSize = bytes.size.toLong(),
            charsetName = decoded.charsetName,
            bom = decoded.bom,
        )
    }

    private fun decode(bytes: ByteArray): DecodedDocument {
        val bom = when {
            bytes.startsWithBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            bytes.startsWithBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            bytes.startsWithBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> byteArrayOf()
        }
        val charset = when {
            bom.contentEquals(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> StandardCharsets.UTF_16LE
            bom.contentEquals(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> StandardCharsets.UTF_16BE
            else -> StandardCharsets.UTF_8
        }
        val payload = bytes.copyOfRange(bom.size, bytes.size)
        val strict = runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload))
                .toString()
        }.getOrNull()
        if (strict != null) return DecodedDocument(strict, charset.name(), bom)
        val controlBytes = payload.count { byte ->
            val value = byte.toInt() and 0xFF
            value == 0 || value in 1..8 || value in 14..31
        }
        require(payload.isEmpty() || controlBytes * 20 <= payload.size) {
            "Binary files cannot be edited safely in the code editor."
        }
        return DecodedDocument(payload.toString(StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1.name(), byteArrayOf())
    }

    private fun atomicWrite(file: File, document: DocumentBuffer, content: String) {
        file.parentFile?.mkdirs()
        val wasExecutable = file.canExecute()
        val wasReadable = file.canRead()
        val wasWritable = file.canWrite()
        val charset = java.nio.charset.Charset.forName(document.charsetName)
        val body = content.toByteArray(charset)
        val bytes = document.bom + body
        val temp = File(file.parentFile, ".orion-save-${file.name}-${System.nanoTime()}")
        try {
            temp.writeBytes(bytes)
            runCatching {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (wasExecutable) file.setExecutable(true, false)
            if (wasReadable) file.setReadable(true, false)
            if (wasWritable) file.setWritable(true, false)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    companion object {
        private const val MAX_PROJECT_FILES = 5_000
        private const val MAX_EDITABLE_BYTES = 8L * 1024 * 1024
        private const val AUTO_SAVE_DELAY_MS = 1_200L

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
