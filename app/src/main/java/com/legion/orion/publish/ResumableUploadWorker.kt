package com.legion.orion.publish

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.legion.orion.core.database.JobsDao
import com.legion.orion.core.database.PublishJobEntity
import com.legion.orion.data.publish.PublishMeta
import com.legion.orion.data.publish.UploadCheckpoint
import com.legion.orion.data.publish.YouTubeAuth
import com.legion.orion.data.publish.YouTubeUploader
import com.legion.orion.data.publish.toVideoMetadata
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException

/**
 * Runs (and resumes) a YouTube upload. WorkManager guarantees it survives process
 * death and reboots; the [PublishJobEntity.uploadSessionJson] checkpoint (session
 * URI + byte offset) is persisted after every 8 MB chunk, so a kill mid-upload
 * continues from the exact byte rather than restarting. Deps are pulled via a Hilt
 * EntryPoint (no hilt-work dependency needed).
 */
class ResumableUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun jobs(): JobsDao
        fun uploader(): YouTubeUploader
        fun auth(): YouTubeAuth
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val jobs = deps.jobs()
        val job = jobs.publishJob(jobId) ?: return Result.failure()
        val file = File(job.mediaPath)
        if (!file.exists()) {
            jobs.upsertPublishJob(job.copy(state = "failed"))
            return Result.failure()
        }

        val token = deps.auth().accessToken()
        if (token.isNullOrBlank()) {
            jobs.upsertPublishJob(job.copy(state = "failed"))
            return Result.failure() // not connected — user must connect YouTube
        }

        val meta = runCatching { json.decodeFromString(PublishMeta.serializer(), job.metadataJson ?: "{}") }
            .getOrDefault(PublishMeta(title = file.nameWithoutExtension))
        val uploader = deps.uploader()

        return try {
            jobs.upsertPublishJob(job.copy(state = "uploading"))
            // Resume from a saved checkpoint, else open a new session.
            var checkpoint = parseCheckpoint(job.uploadSessionJson)
            if (checkpoint == null) {
                checkpoint = uploader.openSession(token, file, meta.toVideoMetadata())
                jobs.upsertPublishJob(job.copy(uploadSessionJson = encode(checkpoint)))
            } else {
                val offset = uploader.queryOffset(checkpoint.sessionUri, file.length())
                checkpoint = checkpoint.copy(offset = offset)
            }

            uploader.upload(
                file = file,
                checkpoint = checkpoint,
                persist = { cp ->
                    jobs.publishJob(jobId)?.let { jobs.upsertPublishJob(it.copy(uploadSessionJson = encode(cp))) }
                },
            )
            jobs.publishJob(jobId)?.let { jobs.upsertPublishJob(it.copy(state = "done")) }
            Result.success()
        } catch (e: IOException) {
            // Transient network error — WorkManager retries (resumes from checkpoint).
            Result.retry()
        } catch (e: Exception) {
            jobs.publishJob(jobId)?.let { jobs.upsertPublishJob(it.copy(state = "failed")) }
            Result.failure()
        }
    }

    private fun parseCheckpoint(jsonStr: String?): UploadCheckpoint? {
        if (jsonStr.isNullOrBlank()) return null
        return runCatching {
            val o = json.parseToJsonElement(jsonStr) as JsonObject
            val uri = o["sessionUri"]?.jsonPrimitive?.contentOrNull ?: return null
            UploadCheckpoint(uri, o["offset"]?.jsonPrimitive?.longOrNull ?: 0)
        }.getOrNull()
    }

    private fun encode(cp: UploadCheckpoint): String =
        """{"sessionUri":"${cp.sessionUri}","offset":${cp.offset}}"""

    companion object {
        const val KEY_JOB_ID = "job_id"
    }
}
