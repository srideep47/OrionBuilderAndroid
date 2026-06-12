package com.legion.orion.data.media

import android.content.Context
import android.util.Log
import com.legion.orion.core.common.Dispatcher
import com.legion.orion.core.common.OrionDispatchers
import com.legion.orion.core.database.JobsDao
import com.legion.orion.core.database.MediaJobEntity
import com.legion.orion.infra.p2p.P2pController
import com.legion.orion.infra.p2p.P2pEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the media-job lifecycle on the phone. Image/video generation NEVER runs
 * locally (non-negotiable) — jobs are submitted to a compute-capable P2P peer,
 * progress is tracked from [P2pController.mediaEvents], and results are decoded
 * into the on-device gallery. Mirrors the desktop media_queue schema.
 */
@Singleton
class MediaManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: P2pController,
    private val jobs: JobsDao,
    @Dispatcher(OrionDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val json = Json { ignoreUnknownKeys = true }

    val galleryDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "media").apply { mkdirs() }

    init {
        scope.launch { controller.mediaEvents.collect { handle(it) } }
    }

    /** True when at least one trusted peer is advertising spare compute. */
    fun hasComputePeer(): Boolean = controller.peers.value.any { it.isTrusted && it.computeAvailable }

    /**
     * Queue a media job. Returns the job id immediately; the job lands in Room as
     * `assigned` (a peer was picked) or `queued` (no peer yet — it waits).
     */
    suspend fun queue(type: String, prompt: String, paramsJson: String? = null): String {
        val jobId = UUID.randomUUID().toString()
        val peer = controller.peers.value.firstOrNull { it.isTrusted && it.computeAvailable }
        val entity = MediaJobEntity(
            id = jobId,
            type = type,
            prompt = prompt,
            paramsJson = paramsJson,
            state = if (peer != null) "assigned" else "queued",
            assignedPeer = peer?.publicKey,
            resultPath = null,
            progress = 0,
            createdAt = System.currentTimeMillis(),
        )
        jobs.upsertMediaJob(entity)
        if (peer != null) {
            val params: JsonElement? = paramsJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            val payload = buildJsonObject {
                put("type", type)
                put("prompt", prompt)
                if (params != null) put("params", params)
            }
            runCatching { controller.submitMediaJob(peer.publicKey, jobId, payload) }
                .onFailure { update(jobId) { it.copy(state = "failed") } }
        }
        return jobId
    }

    suspend fun cancel(jobId: String) {
        val job = jobs.mediaJob(jobId) ?: return
        job.assignedPeer?.let { runCatching { controller.cancelMediaJob(it, jobId) } }
        update(jobId) { it.copy(state = "failed") }
    }

    suspend fun delete(jobId: String) {
        jobs.mediaJob(jobId)?.resultPath?.let { runCatching { File(it).delete() } }
        jobs.deleteMediaJob(jobId)
    }

    private suspend fun handle(event: P2pEvent) {
        when (event) {
            is P2pEvent.MediaAccepted -> update(event.jobId) { it.copy(state = "running") }
            is P2pEvent.MediaProgress -> update(event.jobId) {
                it.copy(state = "running", progress = (event.progress * 100).toInt().coerceIn(0, 100))
            }
            is P2pEvent.MediaResult -> {
                val path = saveResult(event.jobId, event.fileName, event.mimeType, event.dataBase64)
                update(event.jobId) { it.copy(state = "done", progress = 100, resultPath = path) }
            }
            is P2pEvent.MediaError -> update(event.jobId) { it.copy(state = "failed") }
            else -> Unit
        }
    }

    private fun saveResult(jobId: String, fileName: String, mime: String, base64: String): String? = runCatching {
        val ext = fileName.substringAfterLast('.', "").ifBlank { extFor(mime) }
        val out = File(galleryDir, "$jobId.$ext")
        out.writeBytes(Base64.getDecoder().decode(base64))
        out.absolutePath
    }.getOrElse { Log.w(TAG, "saveResult failed", it); null }

    private fun extFor(mime: String) = when {
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("webp") -> "webp"
        mime.contains("mp4") -> "mp4"
        mime.contains("webm") -> "webm"
        else -> "bin"
    }

    private suspend fun update(jobId: String, transform: (MediaJobEntity) -> MediaJobEntity) {
        val current = jobs.mediaJob(jobId) ?: return
        jobs.upsertMediaJob(transform(current))
    }

    private companion object { const val TAG = "MediaManager" }
}
