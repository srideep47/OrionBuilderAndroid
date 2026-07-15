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

    /** Creates a genuinely empty project directory — no scaffold template copied in. For Claude Code
     * (and any other "build from scratch" flow) where the CLI itself should decide what to create,
     * rather than starting from Orion's own web/Kotlin scaffold boilerplate. */
    suspend fun createBlankProject(name: String, stack: String = "custom"): ProjectEntity =
        withContext(Dispatchers.IO) {
            val id = "proj_${System.currentTimeMillis()}"
            dirFor(id).mkdirs()
            val entity = ProjectEntity(
                id = id,
                name = name,
                path = dirFor(id).absolutePath,
                stackType = stack,
                templateId = null,
                gitInitialized = false,
                createdAt = System.currentTimeMillis(),
                lastOpenedAt = System.currentTimeMillis(),
            )
            projectDao.upsert(entity)
            entity
        }

    /** Renames a project's display name (does not touch its on-disk directory name/id). */
    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        projectDao.get(id)?.let { projectDao.upsert(it.copy(name = name)) }
    }

    /** Reserves a fresh project id/directory without creating anything on disk or in Room yet — for
     * flows (like git clone) that need the final directory path up front but shouldn't register the
     * project until the operation that populates it actually succeeds. */
    fun newProjectId(): String = "proj_${System.currentTimeMillis()}"

    /** Registers a project whose directory at [dirFor] was already populated by the caller (e.g. a
     * successful git clone) — unlike [createProject], nothing is scaffolded here. */
    suspend fun registerProject(id: String, name: String, stack: String = "web"): ProjectEntity =
        withContext(Dispatchers.IO) {
            val entity = ProjectEntity(
                id = id,
                name = name,
                path = dirFor(id).absolutePath,
                stackType = stack,
                templateId = null,
                gitInitialized = true,
                createdAt = System.currentTimeMillis(),
                lastOpenedAt = System.currentTimeMillis(),
            )
            projectDao.upsert(entity)
            entity
        }
}
