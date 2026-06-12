package com.legion.orion.data.publish

import com.legion.orion.core.common.Dispatcher
import com.legion.orion.core.common.OrionDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/** Result of opening (or resuming) a resumable session, plus the live offset. */
data class UploadCheckpoint(val sessionUri: String, val offset: Long)

/** Metadata for a YouTube video upload. */
data class VideoMetadata(
    val title: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val privacy: String = "unlisted", // public | unlisted | private
    val categoryId: String = "22",
)

/**
 * YouTube Data API v3 resumable upload — ported from the desktop youtube-publish.ts.
 *   1. POST .../videos?uploadType=resumable → session URL in the Location header.
 *   2. PUT the file in 8 MB chunks with Content-Range. 308 = keep going (the
 *      checkpoint is persisted after every chunk so an interrupted upload resumes
 *      from the exact byte); 200/201 = done, body carries the video id.
 */
@Singleton
class YouTubeUploader @Inject constructor(
    private val http: OkHttpClient,
    @Dispatcher(OrionDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /** Open a fresh resumable session and return its URI. */
    suspend fun openSession(accessToken: String, file: File, meta: VideoMetadata): UploadCheckpoint =
        withContext(io) {
            val body = buildString {
                append("{\"snippet\":{")
                append("\"title\":${jsonStr(meta.title)},")
                append("\"description\":${jsonStr(meta.description)},")
                append("\"tags\":[${meta.tags.joinToString(",") { jsonStr(it) }}],")
                append("\"categoryId\":${jsonStr(meta.categoryId)}")
                append("},\"status\":{\"privacyStatus\":${jsonStr(meta.privacy)}}}")
            }
            val req = Request.Builder()
                .url(START_URL)
                .header("Authorization", "Bearer $accessToken")
                .header("X-Upload-Content-Length", file.length().toString())
                .header("X-Upload-Content-Type", "video/*")
                .post(body.toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { r ->
                require(r.isSuccessful) { "openSession HTTP ${r.code}: ${r.body?.string()}" }
                val loc = r.header("Location") ?: error("No resumable session Location header")
                UploadCheckpoint(loc, 0)
            }
        }

    /** Ask the server how many bytes it already has (for resume after a crash). */
    suspend fun queryOffset(sessionUri: String, total: Long): Long = withContext(io) {
        val req = Request.Builder()
            .url(sessionUri)
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { r ->
            when (r.code) {
                308 -> r.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0
                200, 201 -> total // already complete
                else -> 0
            }
        }
    }

    /**
     * Upload from [checkpoint].offset to the end. Calls [onProgress] (uploaded,total)
     * and [persist] after each chunk so the offset survives process death. Returns
     * the uploaded video id.
     */
    suspend fun upload(
        file: File,
        checkpoint: UploadCheckpoint,
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> },
        persist: suspend (UploadCheckpoint) -> Unit = {},
    ): String = withContext(io) {
        val total = file.length()
        var offset = checkpoint.offset
        RandomAccessFile(file, "r").use { raf ->
            while (offset < total) {
                coroutineContext.ensureActive()
                val end = minOf(offset + CHUNK, total)
                val len = (end - offset).toInt()
                val buf = ByteArray(len)
                raf.seek(offset)
                raf.readFully(buf)

                val req = Request.Builder()
                    .url(checkpoint.sessionUri)
                    .header("Content-Range", "bytes $offset-${end - 1}/$total")
                    .put(buf.toRequestBody(OCTET, 0, len))
                    .build()
                http.newCall(req).execute().use { r ->
                    when (r.code) {
                        308 -> {
                            offset = r.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: end
                            persist(checkpoint.copy(offset = offset))
                            onProgress(offset, total)
                        }
                        200, 201 -> {
                            offset = total
                            onProgress(total, total)
                            return@withContext parseVideoId(r.body?.string())
                        }
                        else -> error("upload chunk HTTP ${r.code}: ${r.body?.string()}")
                    }
                }
            }
        }
        error("Upload finished without a completion response")
    }

    private fun parseVideoId(body: String?): String {
        val id = body?.let { Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        return id ?: "unknown"
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private companion object {
        const val START_URL =
            "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status"
        const val CHUNK = 8 * 1024 * 1024
        val JSON = "application/json".toMediaType()
        val OCTET = "application/octet-stream".toMediaType()
    }
}
