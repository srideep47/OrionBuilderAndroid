package com.legion.orion.feature.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.core.database.DownloadDao
import com.legion.orion.core.database.LocalModelDao
import com.legion.orion.core.database.LocalModelEntity
import com.legion.orion.core.datastore.SettingsRepository
import com.legion.orion.data.marketplace.MarketplaceApi
import com.legion.orion.data.marketplace.ModelDownloader
import com.legion.orion.data.marketplace.ModelSource
import com.legion.orion.data.marketplace.RemoteModel
import com.legion.orion.engine.mnn.MnnBackend
import com.legion.orion.inference.InferenceController
import com.legion.orion.inference.InferenceService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class ModelsTab { LOCAL, MARKETPLACE }

data class ModelsUiState(
    val tab: ModelsTab = ModelsTab.MARKETPLACE,
    val source: ModelSource = ModelSource.HUGGINGFACE,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<RemoteModel> = emptyList(),
    val error: String? = null,
    val loadingModelId: String? = null,
    val modelsPath: String = "",
    val notice: String? = null,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    application: Application,
    private val api: MarketplaceApi,
    private val downloader: ModelDownloader,
    private val localModelDao: LocalModelDao,
    downloadDao: DownloadDao,
    private val controller: InferenceController,
    private val settings: SettingsRepository,
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(ModelsUiState())
    val ui = _ui.asStateFlow()

    val localModels = localModelDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloads = downloadDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val jobs = mutableMapOf<String, Job>()

    init {
        _ui.value = _ui.value.copy(modelsPath = downloader.modelsFolderPath())
        // Auto-register any model already on disk so it shows up in Local.
        viewModelScope.launch { runCatching { downloader.scanLocalModels() } }
    }

    fun rescan() {
        viewModelScope.launch {
            val added = runCatching { downloader.scanLocalModels() }.getOrDefault(0)
            _ui.value = _ui.value.copy(notice = if (added > 0) "Found $added model(s) on disk" else "No new models found")
        }
    }

    fun importPath(path: String) {
        if (path.isBlank()) return
        viewModelScope.launch {
            val entity = runCatching { downloader.registerPath(path.trim()) }.getOrNull()
            _ui.value = _ui.value.copy(
                notice = if (entity != null) "Imported ${entity.repoId}" else "No config.json found at that path",
            )
        }
    }

    fun clearNotice() { _ui.value = _ui.value.copy(notice = null) }

    fun setTab(tab: ModelsTab) { _ui.value = _ui.value.copy(tab = tab) }
    fun setSource(source: ModelSource) { _ui.value = _ui.value.copy(source = source); search() }
    fun onQuery(q: String) { _ui.value = _ui.value.copy(query = q) }

    fun search() {
        val s = _ui.value
        viewModelScope.launch {
            _ui.value = s.copy(searching = true, error = null)
            val res = runCatching { api.search(s.source, s.query) }
            _ui.value = _ui.value.copy(
                searching = false,
                results = res.getOrDefault(emptyList()),
                error = res.exceptionOrNull()?.message
                    ?: if (res.getOrNull()?.isEmpty() == true) "No models found" else null,
            )
        }
    }

    fun download(model: RemoteModel) {
        val id = downloader.localId(model)
        if (jobs[id]?.isActive == true) return
        jobs[id] = viewModelScope.launch {
            downloader.download(model)
        }
    }

    /** Pause = cancel the job; partial files remain and resume on next download(). */
    fun pause(downloadId: String) { jobs[downloadId]?.cancel() }

    fun deleteLocal(model: LocalModelEntity) {
        viewModelScope.launch {
            localModelDao.delete(model.id)
            runCatching { File(model.localPath).deleteRecursively() }
        }
    }

    fun loadModel(model: LocalModelEntity) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingModelId = model.id)
            InferenceService.start(getApplication())
            val backend = MnnBackend.entries.firstOrNull { it.configValue == model.lastUsedBackend } ?: MnnBackend.CPU
            // GPTee tuned profile so quick-loads get full performance, not MNN defaults.
            val tuned = com.legion.orion.engine.mnn.MnnSettings(
                memory = "low",
                power = "high",
                useCachedMmap = true,
            )
            runCatching { controller.load(model.localPath, backend, tuned) }
                .onSuccess { settings.setLastModel(model.localPath, backend.configValue) }
                .onFailure { _ui.value = _ui.value.copy(error = it.message) }
            _ui.value = _ui.value.copy(loadingModelId = null)
        }
    }
}
