package com.legion.orion.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.core.database.BuildJobEntity
import com.legion.orion.core.database.JobsDao
import com.legion.orion.core.database.ProjectEntity
import com.legion.orion.data.project.ProjectManager
import com.legion.orion.feature.preview.PreviewController
import com.legion.orion.runtime.DevServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

data class WorkspaceDiagnostics(
    val consoleErrors: List<String> = emptyList(),
    val failedRequests: List<String> = emptyList(),
)

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    projects: ProjectManager,
    jobsDao: JobsDao,
    devServerState: DevServerState,
    private val previewController: PreviewController,
) : ViewModel() {
    val projects: StateFlow<List<ProjectEntity>> = projects.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val buildJobs: StateFlow<List<BuildJobEntity>> = jobsDao.observeBuildJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val devServer = devServerState.current

    private val _diagnostics = MutableStateFlow(readDiagnostics())
    val diagnostics = _diagnostics.asStateFlow()

    fun refreshDiagnostics() {
        _diagnostics.value = readDiagnostics()
    }

    fun clearDiagnostics() {
        previewController.clearDiagnostics()
        refreshDiagnostics()
    }

    private fun readDiagnostics(): WorkspaceDiagnostics {
        val d = previewController.diagnostics()
        return WorkspaceDiagnostics(d.consoleErrors, d.failedRequests)
    }
}
