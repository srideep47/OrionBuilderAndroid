package com.legion.orion.p2p

import android.content.Context
import android.os.Build
import android.util.Log
import com.legion.orion.core.database.PeerDao
import com.legion.orion.core.database.PeerEntity
import com.legion.orion.infra.p2p.P2pController
import com.legion.orion.infra.p2p.P2pPeer
import com.legion.orion.infra.p2p.P2pService
import com.legion.orion.runtime.NodeController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App-level glue between the :node runtime and the P2P module. infra:p2p deliberately knows nothing
 * about how Node is launched, so this is where the two meet: ensure the Node host is up, hand its
 * control port to [P2pController], run the foreground [P2pService] for OS-level presence, serve
 * trusted peers' inference on the local engine, and persist seen peers to Room.
 */
@Singleton
class P2pCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nodeController: NodeController,
    private val controller: P2pController,
    private val inferenceServer: P2pInferenceServer,
    private val peerDao: PeerDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Persist every peer sighting so the Network screen can show known-but-offline friends and
        // their last-seen capabilities across restarts.
        scope.launch {
            controller.peers.collect { peers -> peers.forEach { persistPeer(it) } }
        }
    }
    val peers = controller.peers
    val online = controller.online
    val selfKey = controller.selfKey
    val trusted = controller.trusted
    val friendRequests = controller.friendRequests
    val mode = controller.mode
    val lastError = controller.lastError

    suspend fun goOnline(): Boolean {
        if (!nodeController.ensureStarted()) {
            Log.w(TAG, "goOnline: node host did not become healthy")
            return false
        }
        val ok = controller.start(
            hostControlPort = NodeController.CONTROL_PORT,
            displayName = deviceLabel(),
            deviceName = Build.MODEL ?: "phone",
            deviceType = "phone",
            appVersion = appVersion(),
        )
        Log.i(TAG, "goOnline -> $ok (mode=${controller.mode.value})")
        if (ok) {
            P2pService.start(context)
            inferenceServer.start()
        }
        return ok
    }

    suspend fun goOffline() {
        inferenceServer.stop()
        controller.stop(NodeController.CONTROL_PORT)
        P2pService.stop(context)
    }

    suspend fun requestFriend(publicKey: String) = controller.requestFriend(publicKey)
    suspend fun acceptFriend(publicKey: String) = controller.acceptFriend(publicKey)

    private fun deviceLabel(): String {
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }.orEmpty()
        val model = Build.MODEL.orEmpty()
        return listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Orion phone" }
    }

    private fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    private suspend fun persistPeer(peer: P2pPeer) {
        runCatching {
            peerDao.upsert(
                PeerEntity(
                    publicKey = peer.publicKey,
                    name = peer.displayName.ifBlank { peer.fingerprint },
                    platform = peer.deviceType,
                    lastSeen = System.currentTimeMillis(),
                    isFriend = peer.isTrusted,
                    approvalState = if (peer.isTrusted) "approved" else "none",
                    capabilitiesJson = null,
                    lastLoadJson = """{"loadedModels":${peer.loadedModels.joinToString(",", "[", "]") { "\"$it\"" }},"computeAvailable":${peer.computeAvailable}}""",
                ),
            )
        }.onFailure { Log.w(TAG, "peer persist failed", it) }
    }

    private companion object {
        const val TAG = "P2pCoordinator"
    }
}
