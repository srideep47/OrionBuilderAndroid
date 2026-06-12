package com.legion.orion.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "orion_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val LAST_MODEL_PATH = stringPreferencesKey("last_model_path")
        val LAST_BACKEND = stringPreferencesKey("last_backend")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val SELECTED_PROVIDER_ID = stringPreferencesKey("selected_provider_id")
        val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
    }

    val lastModelPath: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.LAST_MODEL_PATH] }

    val lastBackend: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.LAST_BACKEND] }

    val darkTheme: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.DARK_THEME] ?: true }

    val selectedProviderId: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.SELECTED_PROVIDER_ID] }

    val selectedModelId: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.SELECTED_MODEL_ID] }

    suspend fun setLastModel(path: String, backend: String) {
        context.settingsDataStore.edit {
            it[Keys.LAST_MODEL_PATH] = path
            it[Keys.LAST_BACKEND] = backend
        }
    }

    suspend fun setSelectedProvider(providerId: String, modelId: String) {
        context.settingsDataStore.edit {
            it[Keys.SELECTED_PROVIDER_ID] = providerId
            it[Keys.SELECTED_MODEL_ID] = modelId
        }
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DARK_THEME] = enabled }
    }
}
