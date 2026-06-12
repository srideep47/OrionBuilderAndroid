package com.legion.orion.engine.providers.internal

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** One decoded SSE event (the concatenated `data:` lines for a single event block). */
data class SseEvent(val event: String?, val data: String)

/** Shared client: long read timeout for streaming, no call timeout (the stream runs long). */
internal val sharedHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * POSTs [jsonBody] to [url] with [headers] and streams back decoded SSE events. Parses the
 * standard `event:`/`data:` framing; multiple data lines in one block are newline-joined.
 * Throws [HttpStreamException] (retryable flag set) on transport or non-2xx errors.
 */
internal fun postSse(
    url: String,
    headers: Map<String, String>,
    jsonBody: String,
): Flow<SseEvent> = callbackFlow {
    val request = Request.Builder()
        .url(url)
        .post(jsonBody.toRequestBody(JSON_MEDIA))
        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        .build()

    val call = sharedHttpClient.newCall(request)
    val response: Response = try {
        call.execute()
    } catch (t: Throwable) {
        close(HttpStreamException("network error: ${t.message}", retryable = true, cause = t))
        return@callbackFlow
    }

    response.use { resp ->
        val body = resp.body
        if (!resp.isSuccessful || body == null) {
            val errText = body?.string()?.take(500).orEmpty()
            val retryable = resp.code == 429 || resp.code in 500..599
            close(HttpStreamException("HTTP ${resp.code}: $errText", retryable))
            return@use
        }

        val source = body.source()
        val eventName = StringBuilder()
        val dataBuf = StringBuilder()
        try {
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        // Blank line terminates an event block.
                        if (dataBuf.isNotEmpty()) {
                            trySend(
                                SseEvent(
                                    eventName.takeIf { it.isNotEmpty() }?.toString(),
                                    dataBuf.toString(),
                                ),
                            )
                        }
                        eventName.clear()
                        dataBuf.clear()
                    }
                    line.startsWith("event:") -> eventName.append(line.removePrefix("event:").trim())
                    line.startsWith("data:") -> {
                        if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                        dataBuf.append(line.removePrefix("data:").trim())
                    }
                    // ignore comments (":") and other fields (id:, retry:)
                }
            }
            // flush a trailing block with no terminating newline
            if (dataBuf.isNotEmpty()) {
                trySend(SseEvent(eventName.takeIf { it.isNotEmpty() }?.toString(), dataBuf.toString()))
            }
            close()
        } catch (t: Throwable) {
            close(HttpStreamException("stream read error: ${t.message}", retryable = true, cause = t))
        }
    }

    awaitClose { call.cancel() }
}.flowOn(Dispatchers.IO)

internal class HttpStreamException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)
