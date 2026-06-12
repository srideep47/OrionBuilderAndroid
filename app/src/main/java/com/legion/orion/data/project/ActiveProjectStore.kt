package com.legion.orion.data.project

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide active project selection shared by Chat, Editor, Preview, and workspace panels. */
@Singleton
class ActiveProjectStore @Inject constructor() {
    private val _projectId = MutableStateFlow<String?>(null)
    val projectId = _projectId.asStateFlow()

    fun set(projectId: String?) {
        _projectId.value = projectId
    }
}
