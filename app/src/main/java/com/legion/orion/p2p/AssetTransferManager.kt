package com.legion.orion.p2p

import android.util.Base64
import android.util.Log
import com.legion.orion.data.project.ProjectManager
import com.legion.orion.infra.p2p.P2pController
import com.legion.orion.infra.p2p.P2pEvent
import com.legion.orion.util.assetCategoryFor
import com.legion.orion.util.uniqueDestination
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One inbound offer awaiting the user's accept/reject + project choice. */
data class AssetOffer(val peerId: String, val requestId: String, val fileName: String, val sizeBytes: Long)

private class InProgressTransfer(val peerId: String, val projectId: String, val fileName: String) {
    val buffer = ByteArrayOutputStream()
}

/**
 * Android-side receiver for a paired PC peer pushing an asset file (3D model, texture, audio)
 * directly into a project's `assets/<category>/` folder — see [com.legion.orion.util.assetCategoryFor]
 * for the category convention shared with the in-app SAF import feature ([com.legion.orion.util.uniqueDestination]
 * is reused for collision-safe naming). This is a NEW protocol addition (`ASSET_PUSH_*`, see
 * `p2p-bridge.js`) with no desktop "Orion Builder" counterpart yet — unlike [BuildOffloadManager],
 * which mirrors an existing desktop schema byte-for-byte, this side is unverified against a real PC
 * peer; verify with a standalone test script that speaks the same LAN wire protocol.
 */
@Singleton
class AssetTransferManager @Inject constructor(
    private val controller: P2pController,
    private val projectManager: ProjectManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pendingOffers = MutableStateFlow<List<AssetOffer>>(emptyList())
    val pendingOffers = _pendingOffers.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices = _notices.asSharedFlow()

    private val transfers = mutableMapOf<String, InProgressTransfer>()

    init {
        scope.launch { collectEvents() }
    }

    /** User accepted an offer and chose which project it should land in. */
    suspend fun accept(requestId: String, targetProjectId: String) {
        val offer = _pendingOffers.value.firstOrNull { it.requestId == requestId } ?: return
        _pendingOffers.value = _pendingOffers.value.filterNot { it.requestId == requestId }
        transfers[requestId] = InProgressTransfer(offer.peerId, targetProjectId, offer.fileName)
        controller.respondAssetPush(offer.peerId, requestId, accept = true)
    }

    suspend fun reject(requestId: String, reason: String? = null) {
        val offer = _pendingOffers.value.firstOrNull { it.requestId == requestId } ?: return
        _pendingOffers.value = _pendingOffers.value.filterNot { it.requestId == requestId }
        controller.respondAssetPush(offer.peerId, requestId, accept = false, reason = reason)
    }

    private suspend fun collectEvents() {
        controller.assetEvents.collect { event ->
            when (event) {
                is P2pEvent.AssetPushOffer -> onOffer(event)
                is P2pEvent.AssetPushChunk -> onChunk(event)
                is P2pEvent.AssetPushError -> onError(event)
                else -> Unit
            }
        }
    }

    private fun onOffer(event: P2pEvent.AssetPushOffer) {
        _pendingOffers.value = _pendingOffers.value + AssetOffer(event.peerId, event.requestId, event.fileName, event.sizeBytes)
    }

    private suspend fun onChunk(event: P2pEvent.AssetPushChunk) {
        val transfer = transfers[event.requestId] ?: return
        if (event.data.isNotEmpty()) runCatching {
            transfer.buffer.write(Base64.decode(event.data, Base64.NO_WRAP))
        }
        if (event.eof) {
            transfers.remove(event.requestId)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val projectDir = projectManager.dirFor(transfer.projectId)
                    val category = assetCategoryFor(transfer.fileName)
                    val dir = File(projectDir, "assets/$category").apply { mkdirs() }
                    val dest = uniqueDestination(dir, transfer.fileName)
                    dest.writeBytes(transfer.buffer.toByteArray())
                    dest.name
                }
            }
            if (result.isSuccess) {
                controller.sendAssetPushResult(transfer.peerId, event.requestId, ok = true)
                _notices.emit("${result.getOrNull()} added to project")
            } else {
                val message = result.exceptionOrNull()?.message ?: "write failed"
                controller.sendAssetPushResult(transfer.peerId, event.requestId, ok = false, error = message)
                _notices.emit("Asset transfer failed: $message")
                Log.w(TAG, "asset write failed for ${transfer.fileName}", result.exceptionOrNull())
            }
        }
    }

    private suspend fun onError(event: P2pEvent.AssetPushError) {
        transfers.remove(event.requestId)
        _notices.emit("Asset transfer failed: ${event.error}")
    }

    private companion object {
        const val TAG = "AssetTransfer"
    }
}
