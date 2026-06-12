package com.legion.orion.infra.p2p

import android.util.Log
import com.legion.orion.core.datastore.SecretStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * High-level P2P state holder. Drives the in-process bridge through [P2pBridgeClient], collects its
 * SSE event stream into observable [StateFlow]s, and persists the trusted-friend set (via
 * [SecretStore]) so approvals survive restarts.
 *
 * The app layer must ensure the Node host is running and pass its control port to [start]; this
 * class deliberately knows nothing about the :node launcher.
 */
@Singleton
class P2pController @Inject constructor(
    private val identity: P2pIdentity,
    private val bridge: P2pBridgeClient,
    private val secretStore: SecretStore,
) {
    private val scope = CoroutineScope(SupervisorJob()) + kotlinx.coroutines.Dispatchers.Default

    private val _peers = MutableStateFlow<List<P2pPeer>>(emptyList())
    val peers: StateFlow<List<P2pPeer>> = _peers.asStateFlow()

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _selfKey = MutableStateFlow("")
    val selfKey: StateFlow<String> = _selfKey.asStateFlow()

    /** Transport mode reported by the bridge: "full" (DHT+Noise) or "lan" (pure-JS fallback). */
    private val _mode = MutableStateFlow("lan")
    val mode: StateFlow<String> = _mode.asStateFlow()

    /** Last start/transport error, for surfacing in the UI (null when healthy). */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _trusted = MutableStateFlow<Set<String>>(emptySet())
    val trusted: StateFlow<Set<String>> = _trusted.asStateFlow()

    /** Incoming friend requests awaiting the user's approval. */
    private val _friendRequests = MutableSharedFlow<FriendRequest>(extraBufferCapacity = 16)
    val friendRequests: SharedFlow<FriendRequest> = _friendRequests.asSharedFlow()

    /** Inbound inference requests a trusted peer asked us to run (handed to the engine layer). */
    private val _inferenceRequests = MutableSharedFlow<P2pEvent.InferenceRequested>(extraBufferCapacity = 16)
    val inferenceRequests: SharedFlow<P2pEvent.InferenceRequested> = _inferenceRequests.asSharedFlow()

    /** Cancellations for inbound inference requests we are currently serving. */
    private val _inferenceCancels = MutableSharedFlow<P2pEvent.InferenceCancelled>(extraBufferCapacity = 16)
    val inferenceCancels: SharedFlow<P2pEvent.InferenceCancelled> = _inferenceCancels.asSharedFlow()

    /** Replies for OUR outbound inference requests (chunk/done/error keyed by requestId). */
    private val _inferenceReplies = MutableSharedFlow<P2pEvent>(extraBufferCapacity = 64)
    val inferenceReplies: SharedFlow<P2pEvent> = _inferenceReplies.asSharedFlow()

    /** Media job lifecycle events from worker peers (accepted/progress/result/error). */
    private val _mediaEvents = MutableSharedFlow<P2pEvent>(extraBufferCapacity = 64)
    val mediaEvents: SharedFlow<P2pEvent> = _mediaEvents.asSharedFlow()

    /** Build offload lifecycle events from worker peers (accepted/log/result). */
    private val _buildEvents = MutableSharedFlow<P2pEvent>(extraBufferCapacity = 64)
    val buildEvents: SharedFlow<P2pEvent> = _buildEvents.asSharedFlow()

    private var eventJob: Job? = null

    @Volatile private var started = false

    /** Boots the bridge with this device's identity + metadata and begins consuming events. */
    suspend fun start(
        hostControlPort: Int,
        displayName: String,
        deviceName: String,
        deviceType: String = "phone",
        appVersion: String = "",
    ): Boolean {
        val seed = identity.seedHex()
        val trustedKeys = loadTrusted()
        _trusted.value = trustedKeys

        val config = BridgeConfig(
            controlPort = P2pBridgeClient.DEFAULT_BRIDGE_PORT,
            seedHex = seed,
            displayName = displayName,
            deviceName = deviceName,
            deviceType = deviceType,
            appVersion = appVersion,
            lanPort = P2pBridgeClient.DEFAULT_LAN_PORT,
        )
        val result = bridge.startBridge(hostControlPort, config)
        _selfKey.value = result.self
        _online.value = result.ok
        _mode.value = result.mode
        if (!result.ok) {
            val reason = result.swarmError ?: result.nativeError ?: "bridge start failed (no response)"
            Log.w(TAG, "bridge start not ok: $reason")
            _lastError.value = reason
            return false
        }
        Log.i(TAG, "bridge online: self=${result.self.take(16)}… mode=${result.mode}")
        _lastError.value = null
        bridge.setTrust(trustedKeys.toList())
        startEventCollection()
        started = true
        return true
    }

    suspend fun stop(hostControlPort: Int) {
        eventJob?.cancel(); eventJob = null
        bridge.stopBridge(hostControlPort)
        _online.value = false
        _peers.value = emptyList()
        started = false
    }

    fun isStarted() = started

    suspend fun requestFriend(publicKey: String) = bridge.friendRequest(publicKey)

    /** Approve an incoming request: trust locally (persisted), notify the peer, sync the bridge. */
    suspend fun acceptFriend(publicKey: String) {
        addTrusted(publicKey)
        bridge.friendAccept(publicKey)
        bridge.setTrust(_trusted.value.toList())
    }

    suspend fun setCompute(state: ComputeState) = bridge.setCompute(state)

    suspend fun respondInferenceChunk(peerId: String, requestId: String, data: kotlinx.serialization.json.JsonElement) =
        bridge.respondInferenceChunk(peerId, requestId, data)

    suspend fun respondInferenceDone(peerId: String, requestId: String) =
        bridge.respondInferenceDone(peerId, requestId)

    suspend fun respondInferenceError(peerId: String, requestId: String, error: String) =
        bridge.respondInferenceError(peerId, requestId, error)

    suspend fun requestInference(peerId: String, requestId: String, body: kotlinx.serialization.json.JsonElement) =
        bridge.requestInference(peerId, requestId, body)

    suspend fun cancelInference(peerId: String, requestId: String) = bridge.cancelInference(peerId, requestId)

    suspend fun submitMediaJob(peerId: String, jobId: String, job: kotlinx.serialization.json.JsonElement) =
        bridge.submitMediaJob(peerId, jobId, job)

    suspend fun cancelMediaJob(peerId: String, jobId: String) = bridge.cancelMediaJob(peerId, jobId)

    suspend fun submitBuildJob(peerId: String, jobId: String, job: kotlinx.serialization.json.JsonElement, bundleBase64: String) =
        bridge.submitBuildJob(peerId, jobId, job, bundleBase64)

    suspend fun cancelBuildJob(peerId: String, jobId: String) = bridge.cancelBuildJob(peerId, jobId)

    private fun startEventCollection() {
        eventJob?.cancel()
        eventJob = scope.launch {
            bridge.events()
                .retryWhen { cause, _ ->
                    Log.w(TAG, "SSE dropped, reconnecting", cause)
                    delay(1000)
                    started // keep retrying only while we mean to be online
                }
                .collect { onEvent(it) }
        }
    }

    private suspend fun onEvent(event: P2pEvent) {
        when (event) {
            is P2pEvent.Status -> {
                _online.value = event.isOnline
                _mode.value = event.mode
                if (event.self.isNotEmpty()) _selfKey.value = event.self
            }
            is P2pEvent.PeersChanged -> _peers.value = event.peers
            is P2pEvent.PeerOnline -> _peers.value = mergePeer(_peers.value, event.peer)
            is P2pEvent.FriendRequestReceived -> _friendRequests.emit(event.request)
            is P2pEvent.FriendAccepted -> addTrusted(event.publicKey)
            is P2pEvent.InferenceRequested -> _inferenceRequests.emit(event)
            is P2pEvent.InferenceCancelled -> _inferenceCancels.emit(event)
            is P2pEvent.InferenceChunk, is P2pEvent.InferenceDone, is P2pEvent.InferenceError ->
                _inferenceReplies.emit(event)
            is P2pEvent.MediaAccepted, is P2pEvent.MediaProgress, is P2pEvent.MediaResult, is P2pEvent.MediaError ->
                _mediaEvents.emit(event)
            is P2pEvent.BuildAccepted, is P2pEvent.BuildLog, is P2pEvent.BuildResult ->
                _buildEvents.emit(event)
            is P2pEvent.Unknown -> Unit
        }
    }

    private fun mergePeer(current: List<P2pPeer>, peer: P2pPeer): List<P2pPeer> {
        val others = current.filterNot { it.publicKey == peer.publicKey }
        return others + peer
    }

    private suspend fun addTrusted(publicKey: String) {
        if (publicKey.isBlank() || _trusted.value.contains(publicKey)) return
        val next = _trusted.value + publicKey
        _trusted.value = next
        secretStore.put(TRUSTED_KEY, next.joinToString(","))
    }

    private suspend fun loadTrusted(): Set<String> =
        secretStore.get(TRUSTED_KEY)?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private companion object {
        const val TAG = "P2pController"
        const val TRUSTED_KEY = "p2p_trusted_keys"
    }
}
