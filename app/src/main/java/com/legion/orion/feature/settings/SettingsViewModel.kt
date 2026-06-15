package com.legion.orion.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.core.database.ProviderEntity
import com.legion.orion.core.datastore.SettingsRepository
import com.legion.orion.data.providers.ProviderRepository
import com.legion.orion.data.providers.ProviderType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class ProviderRow(
    val provider: ProviderEntity,
    val hasKey: Boolean,
    val needsBaseUrl: Boolean,
)

data class SettingsUiState(
    val rows: List<ProviderRow> = emptyList(),
    val compactionEnabled: Boolean = true,
    val compactionThresholdPercent: Int = 75,
    val compactionKeepRecentTurns: Int = 6,
    val compactionNotify: Boolean = true,
    val maxAgentIterations: Int = 12,
    val hapticsEnabled: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val providerRepo: ProviderRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui = _ui.asStateFlow()

    init {
        viewModelScope.launch { providerRepo.seedDefaultsIfEmpty() }
        providerRepo.observeProviders()
            .onEach { providers -> refresh(providers) }
            .launchIn(viewModelScope)
        combine(
            settings.compactionEnabled,
            settings.compactionThresholdPercent,
            settings.compactionKeepRecentTurns,
            settings.compactionNotify,
        ) { enabled, threshold, keepRecent, notify ->
            _ui.value.copy(
                compactionEnabled = enabled,
                compactionThresholdPercent = threshold,
                compactionKeepRecentTurns = keepRecent,
                compactionNotify = notify,
            )
        }.onEach { _ui.value = it }.launchIn(viewModelScope)
        combine(
            settings.maxAgentIterations,
            settings.hapticsEnabled,
        ) { maxIter, haptics ->
            _ui.value.copy(maxAgentIterations = maxIter, hapticsEnabled = haptics)
        }.onEach { _ui.value = it }.launchIn(viewModelScope)
    }

    private suspend fun refresh(providers: List<ProviderEntity>) {
        val rows = providers
            .filter { it.type != ProviderType.MNN_LOCAL }
            .map { p ->
                ProviderRow(
                    provider = p,
                    hasKey = providerRepo.hasKey(p.id),
                    needsBaseUrl = p.type == ProviderType.OPENAI_COMPAT,
                )
            }
        _ui.value = _ui.value.copy(rows = rows)
    }

    fun saveKey(provider: ProviderEntity, key: String) {
        viewModelScope.launch {
            providerRepo.setKey(provider.id, key.trim())
            refresh(providerRepo.observeProviders().first())
        }
    }

    fun saveBaseUrl(provider: ProviderEntity, baseUrl: String) {
        viewModelScope.launch { providerRepo.setBaseUrl(provider, baseUrl.trim()) }
    }

    fun setCompactionEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setCompactionEnabled(enabled) }
    }

    fun setCompactionThresholdPercent(value: Int) {
        viewModelScope.launch { settings.setCompactionThresholdPercent(value) }
    }

    fun setCompactionKeepRecentTurns(value: Int) {
        viewModelScope.launch { settings.setCompactionKeepRecentTurns(value) }
    }

    fun setCompactionNotify(enabled: Boolean) {
        viewModelScope.launch { settings.setCompactionNotify(enabled) }
    }

    fun setMaxAgentIterations(value: Int) {
        viewModelScope.launch { settings.setMaxAgentIterations(value) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setHapticsEnabled(enabled) }
    }
}
