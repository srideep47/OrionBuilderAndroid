package com.legion.orion.infra.p2p

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Kotlin mirrors of the p2p-bridge.js wire JSON. The bridge speaks the desktop Orion Builder
 * protocol byte-for-byte; these types only describe the localhost control API + SSE envelope
 * between Kotlin and the in-process bridge, never the peer wire itself.
 */

/** A peer as the bridge reports it (see placeholderPeer/METADATA handling in p2p-bridge.js). */
@Serializable
data class P2pPeer(
    val publicKey: String,
    val fingerprint: String = "",
    val displayName: String = "",
    val deviceName: String = "—",
    val deviceType: String = "desktop",
    /** connecting | online */
    val status: String = "connecting",
    val isTrusted: Boolean = false,
    val isLan: Boolean = false,
    val latencyMs: Long? = null,
    val loadedModels: List<String> = emptyList(),
    val gpuUtilization: Double = 0.0,
    val computeAvailable: Boolean = false,
    val appVersion: String = "",
)

/**
 * A sharable media item as advertised in a MEDIA_ANNOUNCE. Mirrors the desktop `SharedMediaMeta`
 * (peer-channel.ts) byte-for-byte so phone and desktop catalogs interoperate.
 */
@Serializable
data class SharedMediaItem(
    val fileName: String,
    /** image | video | audio | model */
    val kind: String = "image",
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0,
    val prompt: String? = null,
    /** Small base64 data-URL thumbnail (optional). */
    val thumbnail: String? = null,
)

@Serializable
data class FriendRequest(
    val fromPublicKey: String,
    val fromDisplayName: String = "",
    val fromDeviceName: String = "",
)

/** Compute-load payload broadcast to peers (LOAD_UPDATE) and pushed to the bridge via /compute/set. */
@Serializable
data class ComputeState(
    val gpuUtilization: Double = 0.0,
    val loadedModels: List<String> = emptyList(),
    val computeAvailable: Boolean = false,
    val queueDepth: Int = 0,
)

/** Startup config injected into the bridge (identity seed + this device's display metadata). */
@Serializable
data class BridgeConfig(
    val controlPort: Int,
    val seedHex: String,
    val displayName: String,
    val deviceName: String,
    val deviceType: String = "phone",
    val appVersion: String = "",
    val lanPort: Int = 38291,
    /**
     * Whether the bridge may require() the hyperswarm native addons. Always false on-device:
     * nodejs-mobile silently exit(1)s on process.dlopen of any .node addon, so the bridge runs in
     * pure-JS LAN mode there (authenticated TCP channels) and upgrades to full DHT automatically
     * once addon loading works.
     */
    val useNatives: Boolean = false,
)

@Serializable
data class P2pStatus(
    val isOnline: Boolean = false,
    val self: String = "",
    /** "full" (hyperswarm DHT + Noise) or "lan" (pure-JS LAN fallback). */
    val mode: String = "lan",
    val nativeError: String? = null,
    val peers: List<P2pPeer> = emptyList(),
)

/** Result of POST /p2p/start on the host (echoes self pubkey + the bridge's control port). */
@Serializable
data class BridgeStartResult(
    val ok: Boolean = false,
    val self: String = "",
    val controlPort: Int = 0,
    val mode: String = "lan",
    val nativeError: String? = null,
    val alreadyRunning: Boolean = false,
    val swarmError: String? = null,
)

/**
 * Decoded SSE event from the bridge's /events stream. The raw envelope is {event, data}; the data
 * shape varies per event (see emit(...) calls in p2p-bridge.js).
 */
sealed interface P2pEvent {
    data class Status(val isOnline: Boolean, val self: String, val mode: String = "lan") : P2pEvent
    data class PeersChanged(val peers: List<P2pPeer>) : P2pEvent
    data class PeerOnline(val peer: P2pPeer) : P2pEvent
    data class FriendRequestReceived(val request: FriendRequest) : P2pEvent
    data class FriendAccepted(val publicKey: String) : P2pEvent
    data class InferenceRequested(val peerId: String, val requestId: String, val body: JsonElement) : P2pEvent
    data class InferenceCancelled(val peerId: String, val requestId: String) : P2pEvent
    data class InferenceChunk(val requestId: String, val data: JsonElement) : P2pEvent
    data class InferenceDone(val requestId: String) : P2pEvent
    data class InferenceError(val requestId: String, val error: String) : P2pEvent
    // Inline one-shot generation (we requested; a peer streams the asset back).
    data class MediaGenChunk(val peerId: String, val requestId: String, val data: String, val eof: Boolean) : P2pEvent
    data class MediaGenError(val peerId: String, val requestId: String, val error: String) : P2pEvent
    // Media sharing catalog (browse/serve media shared across the network).
    data class MediaAnnounce(val peerId: String, val items: List<SharedMediaItem>) : P2pEvent
    data class MediaListRequest(val peerId: String) : P2pEvent
    data class MediaDownloadRequest(val peerId: String, val requestId: String, val fileName: String) : P2pEvent
    data class MediaDownloadChunk(val peerId: String, val requestId: String, val data: String, val eof: Boolean) : P2pEvent
    data class MediaDownloadError(val peerId: String, val requestId: String, val error: String) : P2pEvent
    data class BuildAccepted(val peerId: String, val jobId: String) : P2pEvent
    data class BuildLog(val peerId: String, val jobId: String, val line: String) : P2pEvent
    data class BuildResult(val peerId: String, val jobId: String, val ok: Boolean, val artifactName: String?, val artifactBase64: String?, val error: String?) : P2pEvent
    // Asset push (phone-originated protocol addition — no desktop Orion Builder counterpart exists
    // yet; this does NOT mirror an existing desktop schema like the event families above do).
    data class AssetPushOffer(val peerId: String, val requestId: String, val fileName: String, val sizeBytes: Long) : P2pEvent
    data class AssetPushChunk(val peerId: String, val requestId: String, val data: String, val eof: Boolean) : P2pEvent
    data class AssetPushError(val peerId: String, val requestId: String, val error: String) : P2pEvent
    data class Unknown(val event: String) : P2pEvent
}

// Envelope helpers used by the SSE decoder.
@Serializable
internal data class StatusData(val isOnline: Boolean = false, val self: String = "", val mode: String = "lan")

@Serializable
internal data class FriendAcceptedData(val publicKey: String)

@Serializable
internal data class InferenceRequestData(val peerId: String, val requestId: String, val body: JsonElement)

@Serializable
internal data class InferenceCancelData(val peerId: String, val requestId: String)

@Serializable
internal data class InferenceChunkData(val requestId: String, val data: JsonElement)

@Serializable
internal data class InferenceDoneData(val requestId: String)

@Serializable
internal data class InferenceErrorData(val requestId: String, @SerialName("error") val error: String)

@Serializable
internal data class MediaGenChunkData(val peerId: String = "", val requestId: String, val data: String = "", val eof: Boolean = false)

@Serializable
internal data class MediaGenErrorData(val peerId: String = "", val requestId: String, val error: String = "")

@Serializable
internal data class MediaAnnounceData(val peerId: String, val items: List<SharedMediaItem> = emptyList())

@Serializable
internal data class MediaListRequestData(val peerId: String)

@Serializable
internal data class MediaDownloadRequestData(val peerId: String, val requestId: String, val fileName: String)

@Serializable
internal data class MediaDownloadChunkData(val peerId: String, val requestId: String, val data: String = "", val eof: Boolean = false)

@Serializable
internal data class MediaDownloadErrorData(val peerId: String, val requestId: String, val error: String = "")

@Serializable
internal data class BuildAcceptedData(val peerId: String, val jobId: String)

@Serializable
internal data class BuildLogData(val peerId: String, val jobId: String, val line: String = "")

@Serializable
internal data class BuildResultData(
    val peerId: String,
    val jobId: String,
    val ok: Boolean = false,
    val artifactName: String? = null,
    val artifactBase64: String? = null,
    val error: String? = null,
)

@Serializable
internal data class AssetPushOfferData(val peerId: String, val requestId: String, val fileName: String, val sizeBytes: Long = 0)

@Serializable
internal data class AssetPushChunkData(val peerId: String, val requestId: String, val data: String = "", val eof: Boolean = false)

@Serializable
internal data class AssetPushErrorData(val peerId: String, val requestId: String, val error: String = "")
