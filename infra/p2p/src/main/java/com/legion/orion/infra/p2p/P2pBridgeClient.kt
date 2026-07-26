package com.legion.orion.infra.p2p

import android.util.Log
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Localhost client for the in-process p2p-bridge.js. The host (orion-host.js) starts the bridge on
 * its own control port; thereafter every call hits that port directly. Events arrive over the
 * bridge's SSE `/events` stream and are decoded into [P2pEvent]s.
 *
 * This class only does HTTP — it has no dependency on the :node launcher, so the app layer is
 * responsible for ensuring the Node host is up (NodeController.ensureStarted) before [startBridge].
 */
@Singleton
class P2pBridgeClient @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // No call/read timeout: SSE is a long-lived stream; control calls are local and fast anyway.
    private val client = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile private var bridgePort: Int = DEFAULT_BRIDGE_PORT

    private fun bridgeUrl(path: String) = "http://127.0.0.1:$bridgePort$path"

    /** Tell the Node host to start the bridge in-process, then record the bridge's control port. */
    suspend fun startBridge(hostControlPort: Int, config: BridgeConfig): BridgeStartResult =
        withContext(Dispatchers.IO) {
            bridgePort = config.controlPort
            val body = json.encodeToString(config)
            val text = postRaw("http://127.0.0.1:$hostControlPort/p2p/start", body)
            val result = runCatching { json.decodeFromString<BridgeStartResult>(text) }.getOrNull()
                ?: BridgeStartResult()
            if (result.controlPort != 0) bridgePort = result.controlPort
            result
        }

    suspend fun stopBridge(hostControlPort: Int) = withContext(Dispatchers.IO) {
        runCatching { postRaw("http://127.0.0.1:$hostControlPort/p2p/stop", "{}") }
        Unit
    }

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(bridgeUrl("/health")).build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun status(): P2pStatus? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<P2pStatus>(getRaw(bridgeUrl("/status"))) }.getOrNull()
    }

    suspend fun setTrust(publicKeys: List<String>) = post("/trust/set", buildJsonObject {
        putJsonArray("publicKeys") { publicKeys.forEach { add(it) } }
    })

    suspend fun setCompute(state: ComputeState) =
        post("/compute/set", json.encodeToJsonElement(ComputeState.serializer(), state) as JsonObject)

    suspend fun friendRequest(publicKey: String) =
        post("/friend/request", buildJsonObject { put("publicKey", publicKey) })

    suspend fun friendAccept(publicKey: String) =
        post("/friend/accept", buildJsonObject { put("publicKey", publicKey) })

    suspend fun requestInference(peerId: String, requestId: String, body: JsonElement) =
        post("/inference/request", buildJsonObject {
            put("peerId", peerId); put("requestId", requestId); put("body", body)
        })

    suspend fun cancelInference(peerId: String, requestId: String) =
        post("/inference/cancel", buildJsonObject { put("peerId", peerId); put("requestId", requestId) })

    suspend fun respondInferenceChunk(peerId: String, requestId: String, data: JsonElement) =
        post("/inference/respond", buildJsonObject {
            put("peerId", peerId); put("requestId", requestId); put("data", data)
        })

    suspend fun respondInferenceDone(peerId: String, requestId: String) =
        post("/inference/respond", buildJsonObject {
            put("peerId", peerId); put("requestId", requestId); put("done", true)
        })

    suspend fun respondInferenceError(peerId: String, requestId: String, error: String) =
        post("/inference/respond", buildJsonObject {
            put("peerId", peerId); put("requestId", requestId); put("error", error)
        })

    /** Inline one-shot generation: ask a peer to generate one asset and stream it back. */
    suspend fun requestMediaGen(
        peerId: String, requestId: String, modelType: String, prompt: String, ext: String, options: JsonElement? = null,
    ) = post("/media/gen", buildJsonObject {
        put("peerId", peerId); put("requestId", requestId); put("modelType", modelType)
        put("prompt", prompt); put("ext", ext); if (options != null) put("options", options)
    })

    /** Ask a peer to (re)announce its shared media catalog. */
    suspend fun requestMediaList(peerId: String) =
        post("/media/list", buildJsonObject { put("peerId", peerId) })

    /** Publish our shared catalog — to one peer, or broadcast to all trusted when peerId is null. */
    suspend fun announceMedia(peerId: String?, items: List<SharedMediaItem>) =
        post("/media/announce", buildJsonObject {
            if (peerId != null) put("peerId", peerId)
            put("items", json.encodeToJsonElement(ListSerializer(SharedMediaItem.serializer()), items))
        })

    /** Request the bytes of one shared file from a peer (streamed back as download chunks). */
    suspend fun requestMediaDownload(peerId: String, requestId: String, fileName: String) =
        post("/media/download", buildJsonObject {
            put("peerId", peerId); put("requestId", requestId); put("fileName", fileName)
        })

    /** Serve one base64 chunk of a file a peer requested from us; eof marks the last chunk. */
    suspend fun respondMediaDownloadChunk(peerId: String, requestId: String, dataB64: String, eof: Boolean) =
        post("/media/download/respond", buildJsonObject {
            put("peerId", peerId); put("requestId", requestId); put("data", dataB64); put("eof", eof)
        })

    suspend fun respondMediaDownloadError(peerId: String, requestId: String, error: String) =
        post("/media/download/respond", buildJsonObject {
            put("peerId", peerId); put("requestId", requestId); put("error", error)
        })

    suspend fun submitBuildJob(peerId: String, jobId: String, job: JsonElement, bundleBase64: String) =
        post("/build/submit", buildJsonObject {
            put("peerId", peerId); put("jobId", jobId); put("job", job); put("bundleBase64", bundleBase64)
        })

    suspend fun cancelBuildJob(peerId: String, jobId: String) =
        post("/build/cancel", buildJsonObject { put("peerId", peerId); put("jobId", jobId) })

    /** Accept or reject an inbound [P2pEvent.AssetPushOffer]. */
    suspend fun respondAssetPush(peerId: String, requestId: String, accept: Boolean, reason: String? = null) =
        post("/asset/respond", buildJsonObject {
            put("peerId", peerId); put("requestId", requestId); put("accept", accept)
            if (reason != null) put("reason", reason)
        })

    /** Final ack once the reassembled asset has been written (or failed) locally. */
    suspend fun sendAssetPushResult(peerId: String, requestId: String, ok: Boolean, error: String? = null) =
        post("/asset/result", buildJsonObject {
            put("peerId", peerId); put("requestId", requestId); put("ok", ok)
            if (error != null) put("error", error)
        })

    /**
     * Cold Flow over the bridge SSE stream. One EventSource per collection; on failure/close the
     * flow completes (with cause) so a collector can reconnect via `retryWhen`.
     */
    fun events(): Flow<P2pEvent> = callbackFlow {
        val req = Request.Builder().url(bridgeUrl("/events")).build()
        val listener = object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                decodeEvent(data)?.let { trySend(it) }
            }
            override fun onClosed(es: EventSource) { close() }
            override fun onFailure(es: EventSource, t: Throwable?, response: okhttp3.Response?) {
                close(t ?: RuntimeException("SSE failure"))
            }
        }
        val source = EventSources.createFactory(client).newEventSource(req, listener)
        awaitClose { source.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun decodeEvent(raw: String): P2pEvent? = runCatching {
        val env = json.parseToJsonElement(raw) as? JsonObject ?: return null
        val event = env["event"]?.jsonPrimitive?.content ?: return null
        val data = env["data"]
        when (event) {
            "status" -> json.decodeFromJsonElement(StatusData.serializer(), data!!)
                .let { P2pEvent.Status(it.isOnline, it.self, it.mode) }
            "peers-changed" -> P2pEvent.PeersChanged(decodePeers(data))
            "peer-online" -> P2pEvent.PeerOnline(json.decodeFromJsonElement(P2pPeer.serializer(), data!!))
            "friend-request" -> P2pEvent.FriendRequestReceived(
                json.decodeFromJsonElement(FriendRequest.serializer(), data!!))
            "friend-accepted" -> json.decodeFromJsonElement(FriendAcceptedData.serializer(), data!!)
                .let { P2pEvent.FriendAccepted(it.publicKey) }
            "inference-request" -> json.decodeFromJsonElement(InferenceRequestData.serializer(), data!!)
                .let { P2pEvent.InferenceRequested(it.peerId, it.requestId, it.body) }
            "inference-cancel" -> json.decodeFromJsonElement(InferenceCancelData.serializer(), data!!)
                .let { P2pEvent.InferenceCancelled(it.peerId, it.requestId) }
            "inference-chunk" -> json.decodeFromJsonElement(InferenceChunkData.serializer(), data!!)
                .let { P2pEvent.InferenceChunk(it.requestId, it.data) }
            "inference-done" -> json.decodeFromJsonElement(InferenceDoneData.serializer(), data!!)
                .let { P2pEvent.InferenceDone(it.requestId) }
            "inference-error" -> json.decodeFromJsonElement(InferenceErrorData.serializer(), data!!)
                .let { P2pEvent.InferenceError(it.requestId, it.error) }
            "media-gen-chunk" -> json.decodeFromJsonElement(MediaGenChunkData.serializer(), data!!)
                .let { P2pEvent.MediaGenChunk(it.peerId, it.requestId, it.data, it.eof) }
            "media-gen-error" -> json.decodeFromJsonElement(MediaGenErrorData.serializer(), data!!)
                .let { P2pEvent.MediaGenError(it.peerId, it.requestId, it.error) }
            "media-announce" -> json.decodeFromJsonElement(MediaAnnounceData.serializer(), data!!)
                .let { P2pEvent.MediaAnnounce(it.peerId, it.items) }
            "media-list-request" -> json.decodeFromJsonElement(MediaListRequestData.serializer(), data!!)
                .let { P2pEvent.MediaListRequest(it.peerId) }
            "media-download-request" -> json.decodeFromJsonElement(MediaDownloadRequestData.serializer(), data!!)
                .let { P2pEvent.MediaDownloadRequest(it.peerId, it.requestId, it.fileName) }
            "media-download-chunk" -> json.decodeFromJsonElement(MediaDownloadChunkData.serializer(), data!!)
                .let { P2pEvent.MediaDownloadChunk(it.peerId, it.requestId, it.data, it.eof) }
            "media-download-error" -> json.decodeFromJsonElement(MediaDownloadErrorData.serializer(), data!!)
                .let { P2pEvent.MediaDownloadError(it.peerId, it.requestId, it.error) }
            "build-accepted" -> json.decodeFromJsonElement(BuildAcceptedData.serializer(), data!!)
                .let { P2pEvent.BuildAccepted(it.peerId, it.jobId) }
            "build-log" -> json.decodeFromJsonElement(BuildLogData.serializer(), data!!)
                .let { P2pEvent.BuildLog(it.peerId, it.jobId, it.line) }
            "build-result" -> json.decodeFromJsonElement(BuildResultData.serializer(), data!!)
                .let { P2pEvent.BuildResult(it.peerId, it.jobId, it.ok, it.artifactName, it.artifactBase64, it.error) }
            "asset-push-offer" -> json.decodeFromJsonElement(AssetPushOfferData.serializer(), data!!)
                .let { P2pEvent.AssetPushOffer(it.peerId, it.requestId, it.fileName, it.sizeBytes) }
            "asset-push-chunk" -> json.decodeFromJsonElement(AssetPushChunkData.serializer(), data!!)
                .let { P2pEvent.AssetPushChunk(it.peerId, it.requestId, it.data, it.eof) }
            "asset-push-error" -> json.decodeFromJsonElement(AssetPushErrorData.serializer(), data!!)
                .let { P2pEvent.AssetPushError(it.peerId, it.requestId, it.error) }
            else -> P2pEvent.Unknown(event)
        }
    }.getOrElse { Log.w(TAG, "bad SSE event: $raw", it); null }

    private fun decodePeers(data: JsonElement?): List<P2pPeer> {
        val arr = data as? JsonArray ?: return emptyList()
        return arr.map { json.decodeFromJsonElement(P2pPeer.serializer(), it) }
    }

    private suspend fun post(path: String, body: JsonObject) = withContext(Dispatchers.IO) {
        runCatching { postRaw(bridgeUrl(path), body.toString()) }
            .onFailure { Log.w(TAG, "POST $path failed", it) }
        Unit
    }

    private fun postRaw(url: String, body: String): String {
        val req = Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA)).build()
        return client.newCall(req).execute().use { it.body?.string().orEmpty() }
    }

    private fun getRaw(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }

    companion object {
        private const val TAG = "P2pBridgeClient"
        const val DEFAULT_BRIDGE_PORT = 8138
        const val DEFAULT_LAN_PORT = 38291
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
