package com.legion.orion.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legion.orion.core.database.ProviderEntity
import com.legion.orion.data.providers.ProviderRepository
import com.legion.orion.data.providers.ProviderType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val providerRepo: ProviderRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui = _ui.asStateFlow()

    init {
        viewModelScope.launch { providerRepo.seedDefaultsIfEmpty() }
        providerRepo.observeProviders()
            .onEach { providers -> refresh(providers) }
            .launchIn(viewModelScope)
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
        _ui.value = SettingsUiState(rows)
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
}
