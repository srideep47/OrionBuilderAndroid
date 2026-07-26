package com.legion.orion.feature.network

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.core.database.ProjectEntity
import com.legion.orion.data.project.ActiveProjectStore
import com.legion.orion.data.project.ProjectManager
import com.legion.orion.infra.p2p.FriendRequest
import com.legion.orion.p2p.AssetOffer
import com.legion.orion.p2p.AssetTransferManager
import com.legion.orion.p2p.P2pCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class NetworkViewModel @Inject constructor(
    application: Application,
    private val coordinator: P2pCoordinator,
    private val assetTransfer: AssetTransferManager,
    private val projectManager: ProjectManager,
    private val activeProject: ActiveProjectStore,
) : AndroidViewModel(application) {

    val peers = coordinator.peers
    val online = coordinator.online
    val selfKey = coordinator.selfKey
    val trusted = coordinator.trusted
    val mode = coordinator.mode

    private val _pendingRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val pendingRequests = _pendingRequests.asStateFlow()

    val assetOffers = assetTransfer.pendingOffers
    val projects: kotlinx.coroutines.flow.StateFlow<List<ProjectEntity>> = projectManager.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeProjectId = activeProject.projectId

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /** One-shot user-visible errors (snackbar). */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors = _errors.asSharedFlow()

    init {
        viewModelScope.launch {
            coordinator.friendRequests.collect { req ->
                _pendingRequests.value =
                    _pendingRequests.value.filterNot { it.fromPublicKey == req.fromPublicKey } + req
            }
        }
        viewModelScope.launch {
            assetTransfer.notices.collect { _errors.emit(it) }
        }
    }

    fun acceptAsset(offer: AssetOffer, targetProjectId: String) {
        viewModelScope.launch { assetTransfer.accept(offer.requestId, targetProjectId) }
    }

    fun rejectAsset(offer: AssetOffer) {
        viewModelScope.launch { assetTransfer.reject(offer.requestId) }
    }

    fun toggleOnline(enable: Boolean) {
        viewModelScope.launch {
            _busy.value = true
            try {
                if (enable) {
                    val ok = coordinator.goOnline()
                    if (!ok) {
                        val reason = coordinator.lastError.value ?: "Node host didn't start — check logs"
                        Log.w(TAG, "goOnline failed: $reason")
                        _errors.emit("Couldn't go online: $reason")
                    }
                } else {
                    coordinator.goOffline()
                }
            } catch (e: Exception) {
                Log.e(TAG, "toggleOnline($enable) threw", e)
                _errors.emit("Network error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _busy.value = false
            }
        }
    }

    fun acceptFriend(req: FriendRequest) {
        viewModelScope.launch {
            coordinator.acceptFriend(req.fromPublicKey)
            _pendingRequests.value = _pendingRequests.value.filterNot { it.fromPublicKey == req.fromPublicKey }
        }
    }

    fun dismissRequest(req: FriendRequest) {
        _pendingRequests.value = _pendingRequests.value.filterNot { it.fromPublicKey == req.fromPublicKey }
    }

    fun requestFriend(publicKey: String) {
        viewModelScope.launch { coordinator.requestFriend(publicKey) }
    }

    private companion object {
        const val TAG = "NetworkViewModel"
    }
}
