package com.legion.orion.feature.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.core.database.JobsDao
import com.legion.orion.data.media.MediaManager
import com.legion.orion.data.publish.PublishManager
import com.legion.orion.data.publish.PublishMeta
import com.legion.orion.data.publish.YouTubeAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MediaType(val wire: String, val label: String) {
    IMAGE("image", "Image"),
    VIDEO("video", "Video"),
    AUDIO("audio", "Audio"),
}

data class MediaUiState(
    val type: MediaType = MediaType.IMAGE,
    val prompt: String = "",
    val hasComputePeer: Boolean = false,
    val ytConfigured: Boolean = false,
    val ytConnected: Boolean = false,
)

@HiltViewModel
class MediaViewModel @Inject constructor(
    private val manager: MediaManager,
    private val publish: PublishManager,
    private val youtube: YouTubeAuth,
    jobsDao: JobsDao,
) : ViewModel() {

    private val _ui = MutableStateFlow(MediaUiState())
    val ui = _ui.asStateFlow()

    val jobs = jobsDao.observeMediaJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val publishJobs = jobsDao.observePublishJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refreshPeerState() {
        _ui.value = _ui.value.copy(hasComputePeer = manager.hasComputePeer())
        viewModelScope.launch {
            _ui.value = _ui.value.copy(ytConfigured = youtube.isConfigured(), ytConnected = youtube.isConnected())
        }
    }

    fun setType(t: MediaType) { _ui.value = _ui.value.copy(type = t) }
    fun onPrompt(p: String) { _ui.value = _ui.value.copy(prompt = p) }

    fun queue() {
        val s = _ui.value
        if (s.prompt.isBlank()) return
        viewModelScope.launch {
            manager.queue(s.type.wire, s.prompt.trim())
            _ui.value = _ui.value.copy(prompt = "")
        }
    }

    fun cancel(jobId: String) { viewModelScope.launch { manager.cancel(jobId) } }
    fun delete(jobId: String) { viewModelScope.launch { manager.delete(jobId) } }

    // ---- YouTube publishing ----

    fun saveYouTubeClient(clientId: String, clientSecret: String) {
        viewModelScope.launch {
            youtube.setClient(clientId, clientSecret)
            _ui.value = _ui.value.copy(ytConfigured = youtube.isConfigured())
        }
    }

    /** Builds the consent URL for the UI to open in a browser / custom tab. */
    suspend fun consentUrl(): String? = youtube.consentUrl()

    fun publishVideo(mediaPath: String, title: String) {
        viewModelScope.launch {
            publish.publish(mediaPath, PublishMeta(title = title, privacy = "unlisted"))
        }
    }

    /** Schedules a publish for later — WorkManager fires it even after reboot. */
    fun publishVideoAt(mediaPath: String, title: String, delayMs: Long) {
        viewModelScope.launch {
            publish.publish(
                mediaPath,
                PublishMeta(title = title, privacy = "unlisted"),
                scheduledAt = System.currentTimeMillis() + delayMs,
            )
        }
    }
}
