package com.legion.orion.data.project

import android.content.Context
import com.legion.orion.core.database.ProjectDao
import com.legion.orion.core.database.ProjectEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    companion object {
        /** Singleton pseudo-project id for "Just chat with Claude" — a real Room row and real
         * on-disk dir like any other project, so every existing per-project mechanism (session
         * --resume id, live process map, git snapshot) just works with zero special-casing. Filtered
         * out of [observeProjects] so it never shows up in the normal project list/picker. */
        const val CHAT_ONLY_PROJECT_ID = "chat_only"
    }

    private val root: File get() = File(context.filesDir, "projects").apply { mkdirs() }

    fun dirFor(projectId: String): File = File(root, projectId)

    fun observeProjects(): Flow<List<ProjectEntity>> =
        projectDao.observeAll().map { list -> list.filterNot { it.id == CHAT_ONLY_PROJECT_ID } }

    /** Gets or creates the singleton "Just chat with Claude" pseudo-project — idempotent, safe to
     * call every time the picker's "Just chat" option is chosen. */
    suspend fun ensureChatOnlyProject(): ProjectEntity = withContext(Dispatchers.IO) {
        projectDao.get(CHAT_ONLY_PROJECT_ID)?.let { return@withContext it }
        val dir = dirFor(CHAT_ONLY_PROJECT_ID).apply { mkdirs() }
        val entity = ProjectEntity(
            id = CHAT_ONLY_PROJECT_ID,
            name = "Just chat with Claude",
            path = dir.absolutePath,
            stackType = "chat",
            templateId = null,
            gitInitialized = false,
            createdAt = System.currentTimeMillis(),
            lastOpenedAt = System.currentTimeMillis(),
        )
        projectDao.upsert(entity)
        entity
    }

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

    /** Creates another registered top-level project containing an independent copy of [sourceId]. */
    suspend fun duplicateProject(sourceId: String, requestedName: String? = null): ProjectEntity =
        withContext(Dispatchers.IO) {
            val source = projectDao.get(sourceId) ?: error("Project no longer exists")
            val sourceDir = dirFor(sourceId)
            require(sourceDir.isDirectory) { "Project folder is missing" }
            val id = newProjectId()
            val destination = dirFor(id)
            try {
                copyTreeNoFollow(sourceDir, destination)
                val now = System.currentTimeMillis()
                val baseName = requestedName?.trim()?.takeIf { it.isNotBlank() } ?: "${source.name} copy"
                val copyName = uniqueProjectName(baseName, projectDao.getAllOnce().mapTo(mutableSetOf()) { it.name })
                val entity = source.copy(
                    id = id,
                    name = copyName,
                    path = destination.absolutePath,
                    createdAt = now,
                    lastOpenedAt = now,
                )
                projectDao.upsert(entity)
                entity
            } catch (error: Throwable) {
                deleteTreeNoFollow(destination)
                throw error
            }
        }

    /**
     * Copies or moves a registered top-level project into a directory of another project as a normal
     * folder. A move unregisters the source project only after the filesystem transfer succeeds.
     */
    suspend fun transferProjectInto(
        sourceId: String,
        destinationProjectId: String,
        destinationRelativePath: String,
        move: Boolean,
    ): String = withContext(Dispatchers.IO) {
        require(sourceId != destinationProjectId) { "A project cannot be pasted inside itself" }
        val source = projectDao.get(sourceId) ?: error("Project no longer exists")
        val sourceDir = dirFor(sourceId)
        require(sourceDir.isDirectory) { "Project folder is missing" }
        val destinationRoot = dirFor(destinationProjectId).canonicalFile
        val destinationDir = File(destinationRoot, destinationRelativePath).canonicalFile
        require(destinationDir == destinationRoot || destinationDir.path.startsWith(destinationRoot.path + File.separator)) {
            "Destination is outside the selected project"
        }
        require(destinationDir.isDirectory) { "Destination folder is missing" }
        val baseName = safeFolderName(source.name)
        val destination = uniqueDestination(destinationDir, baseName)
        if (move) {
            val moved = runCatching {
                Files.move(sourceDir.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }.recoverCatching {
                Files.move(sourceDir.toPath(), destination.toPath())
            }.isSuccess
            if (!moved) {
                copyTreeNoFollow(sourceDir, destination)
                deleteTreeNoFollow(sourceDir)
            }
            projectDao.delete(sourceId)
        } else {
            copyTreeNoFollow(sourceDir, destination)
        }
        destination.name
    }

    /** Removes a project registration and its complete on-disk tree without following symlinks. */
    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        val dir = dirFor(id)
        deleteTreeNoFollow(dir)
        projectDao.delete(id)
    }

    private fun safeFolderName(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "-")
        .trim()
        .trimEnd('.')
        .ifBlank { "project" }

    private fun uniqueDestination(parent: File, name: String): File {
        var candidate = File(parent, name)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(parent, "$name ($suffix)")
            suffix++
        }
        return candidate
    }

    private fun uniqueProjectName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var suffix = 2
        var candidate = "$base ($suffix)"
        while (candidate in existing) {
            suffix++
            candidate = "$base ($suffix)"
        }
        return candidate
    }

    /** NIO's default tree walk does not follow symbolic links, preventing cloned-repo links from
     * escaping the source project while a whole project is copied or deleted. */
    private fun copyTreeNoFollow(source: File, destination: File) {
        val sourcePath = source.toPath()
        val destinationPath = destination.toPath()
        Files.walkFileTree(sourcePath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(destinationPath.resolve(sourcePath.relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val target = destinationPath.resolve(sourcePath.relativize(file))
                Files.copy(
                    file,
                    target,
                    StandardCopyOption.COPY_ATTRIBUTES,
                    StandardCopyOption.REPLACE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS,
                )
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteTreeNoFollow(directory: File) {
        if (!directory.exists() && !Files.isSymbolicLink(directory.toPath())) return
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
