package com.legion.orion.p2p

import android.content.Context
import android.util.Base64
import android.util.Log
import com.legion.orion.core.database.BuildJobEntity
import com.legion.orion.core.database.JobsDao
import com.legion.orion.infra.p2p.P2pController
import com.legion.orion.infra.p2p.P2pEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.BundleWriter

/**
 * Offloads native-desktop builds (win/linux/mac) to a trusted peer — phones can't run those
 * toolchains. The project is shipped as a git bundle when it's a repo (the desktop protocol's
 * "git bundle push"), else as a zip; the peer streams BUILD_JOB_LOG lines back and finally a
 * BUILD_JOB_RESULT with the artifact, which lands in files/builds/<jobId>/.
 *
 * Job state is persisted to the Room build_jobs table so queued/running/done survives restarts.
 */
@Singleton
class BuildOffloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: P2pController,
    private val jobsDao: JobsDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Live log lines per running job (jobId → accumulated log). */
    private val _logs = MutableStateFlow<Map<String, String>>(emptyMap())
    val logs = _logs.asStateFlow()

    init {
        scope.launch { collectEvents() }
    }

    val jobs = jobsDao.observeBuildJobs()

    /**
     * Packages the project and submits it to [peerId]. Returns the job id, or throws with a
     * user-readable reason (peer offline, packaging failure).
     */
    suspend fun submit(projectId: String, projectDir: File, target: String, peerId: String): String =
        withContext(Dispatchers.IO) {
            val jobId = UUID.randomUUID().toString()
            val (format, payload) = packageProject(projectDir)
            val job = buildJsonObject {
                put("target", target)
                put("projectName", projectDir.name)
                put("format", format)
            }
            jobsDao.upsertBuildJob(
                BuildJobEntity(
                    id = jobId, projectId = projectId, target = target,
                    state = "queued", peer = peerId, logTail = null, artifactPath = null,
                ),
            )
            controller.submitBuildJob(peerId, jobId, job, Base64.encodeToString(payload, Base64.NO_WRAP))
            Log.i(TAG, "submitted build job $jobId ($target, $format, ${payload.size / 1024}KB) to ${peerId.take(16)}…")
            jobId
        }

    suspend fun cancel(jobId: String) {
        val job = currentJob(jobId) ?: return
        job.peer?.let { controller.cancelBuildJob(it, jobId) }
        jobsDao.upsertBuildJob(job.copy(state = "failed", logTail = appendLog(jobId, "\n[cancelled]")))
    }

    private suspend fun collectEvents() {
        controller.buildEvents.collect { event ->
            when (event) {
                is P2pEvent.BuildAccepted -> currentJob(event.jobId)?.let {
                    jobsDao.upsertBuildJob(it.copy(state = "running"))
                }
                is P2pEvent.BuildLog -> {
                    val tail = appendLog(event.jobId, event.line + "\n")
                    currentJob(event.jobId)?.let { jobsDao.upsertBuildJob(it.copy(logTail = tail)) }
                }
                is P2pEvent.BuildResult -> onResult(event)
                else -> Unit
            }
        }
    }

    private suspend fun onResult(result: P2pEvent.BuildResult) {
        val job = currentJob(result.jobId) ?: return
        if (!result.ok) {
            jobsDao.upsertBuildJob(
                job.copy(state = "failed", logTail = appendLog(result.jobId, "\n[failed] ${result.error ?: "unknown error"}")),
            )
            return
        }
        val artifactPath = result.artifactBase64?.let { b64 ->
            runCatching {
                val dir = File(context.filesDir, "builds/${result.jobId}").apply { mkdirs() }
                val file = File(dir, result.artifactName ?: "artifact.bin")
                file.writeBytes(Base64.decode(b64, Base64.NO_WRAP))
                file.absolutePath
            }.onFailure { Log.w(TAG, "artifact write failed", it) }.getOrNull()
        }
        jobsDao.upsertBuildJob(job.copy(state = "done", artifactPath = artifactPath))
    }

    private fun appendLog(jobId: String, line: String): String {
        val next = ((_logs.value[jobId] ?: "") + line).takeLast(LOG_TAIL_CHARS)
        _logs.value = _logs.value + (jobId to next)
        return next
    }

    suspend fun job(jobId: String): BuildJobEntity? = currentJob(jobId)

    private suspend fun currentJob(jobId: String): BuildJobEntity? =
        jobsDao.observeBuildJobs().first().firstOrNull { it.id == jobId }

    /** Git bundle for repos (all refs), zip otherwise. Returns (format, bytes). */
    private fun packageProject(projectDir: File): Pair<String, ByteArray> {
        if (File(projectDir, ".git").exists()) {
            runCatching {
                Git.open(projectDir).use { git ->
                    val repo = git.repository
                    val writer = BundleWriter(repo)
                    repo.refDatabase.refs.forEach { ref -> writer.include(ref) }
                    val out = java.io.ByteArrayOutputStream()
                    writer.writeBundle(org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE, out)
                    return "bundle" to out.toByteArray()
                }
            }.onFailure { Log.w(TAG, "bundle failed, falling back to zip", it) }
        }
        return "zip" to zipDirectory(projectDir)
    }

    private fun zipDirectory(dir: File): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            dir.walkTopDown()
                .filter { it.isFile && !it.path.contains("${File.separator}node_modules${File.separator}") && !it.path.contains("${File.separator}.git${File.separator}") }
                .forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(dir).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "BuildOffload"
        const val LOG_TAIL_CHARS = 4000
    }
}
