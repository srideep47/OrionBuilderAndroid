package com.legion.orion.data.publish

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.legion.orion.core.database.JobsDao
import com.legion.orion.core.database.PublishJobEntity
import com.legion.orion.publish.ResumableUploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PublishMeta(
    val title: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val privacy: String = "unlisted",
    val categoryId: String = "22",
)

fun PublishMeta.toVideoMetadata() = VideoMetadata(title, description, tags, privacy, categoryId)

/**
 * Schedules YouTube publishes. Each job is a WorkManager request constrained to a
 * network connection (so it survives reboots and resumes after interruption via
 * the checkpoint in [PublishJobEntity.uploadSessionJson]); scheduled publishes use
 * an initial delay.
 */
@Singleton
class PublishManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jobs: JobsDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun publish(mediaPath: String, meta: PublishMeta, scheduledAt: Long? = null): String {
        val jobId = UUID.randomUUID().toString()
        jobs.upsertPublishJob(
            PublishJobEntity(
                id = jobId,
                platform = "youtube",
                mediaPath = mediaPath,
                metadataJson = json.encodeToString(PublishMeta.serializer(), meta),
                scheduledAt = scheduledAt,
                state = if (scheduledAt != null && scheduledAt > System.currentTimeMillis()) "scheduled" else "uploading",
                uploadSessionJson = null,
            ),
        )
        enqueue(jobId, scheduledAt)
        return jobId
    }

    private fun enqueue(jobId: String, scheduledAt: Long?) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val builder = OneTimeWorkRequestBuilder<ResumableUploadWorker>()
            .setInputData(Data.Builder().putString(ResumableUploadWorker.KEY_JOB_ID, jobId).build())
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
        val delay = scheduledAt?.minus(System.currentTimeMillis())
        if (delay != null && delay > 0) builder.setInitialDelay(delay, TimeUnit.MILLISECONDS)
        WorkManager.getInstance(context)
            .enqueueUniqueWork("publish_$jobId", ExistingWorkPolicy.KEEP, builder.build())
    }
}
