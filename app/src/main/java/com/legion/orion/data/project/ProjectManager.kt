package com.legion.orion.data.project

import android.content.Context
import com.legion.orion.core.database.ProjectDao
import com.legion.orion.core.database.ProjectEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Owns project directories under filesDir/projects/<id>/ and their Room rows. For Phase 3 it can
 * seed a tiny static-web fixture so the agent has something real to edit; richer scaffolds arrive
 * in Phase 4.
 */
@Singleton
class ProjectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectDao: ProjectDao,
    private val scaffolds: ScaffoldManager,
) {
    private val root: File get() = File(context.filesDir, "projects").apply { mkdirs() }

    fun dirFor(projectId: String): File = File(root, projectId)

    fun observeProjects(): Flow<List<ProjectEntity>> = projectDao.observeAll()

    suspend fun get(id: String): ProjectEntity? = projectDao.get(id)

    suspend fun createProject(
        name: String,
        templateId: String = "static-web",
        stack: String = "web",
    ): ProjectEntity = withContext(Dispatchers.IO) {
        val id = "proj_${System.currentTimeMillis()}"
        val dir = dirFor(id).apply { mkdirs() }
        runCatching { scaffolds.scaffold(templateId, dir) }
        val entity = ProjectEntity(
            id = id,
            name = name,
            path = dir.absolutePath,
            stackType = stack,
            templateId = templateId,
            gitInitialized = false,
            createdAt = System.currentTimeMillis(),
            lastOpenedAt = System.currentTimeMillis(),
        )
        projectDao.upsert(entity)
        entity
    }

    suspend fun touch(entity: ProjectEntity) =
        projectDao.upsert(entity.copy(lastOpenedAt = System.currentTimeMillis()))
}
