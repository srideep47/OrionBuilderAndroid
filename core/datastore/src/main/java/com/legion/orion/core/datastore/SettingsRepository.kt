package com.legion.orion.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        val COMPACTION_ENABLED = booleanPreferencesKey("compaction_enabled")
        val COMPACTION_THRESHOLD_PERCENT = intPreferencesKey("compaction_threshold_percent")
        val COMPACTION_KEEP_RECENT_TURNS = intPreferencesKey("compaction_keep_recent_turns")
        val COMPACTION_NOTIFY = booleanPreferencesKey("compaction_notify")
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

    val compactionEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.COMPACTION_ENABLED] ?: true }

    val compactionThresholdPercent: Flow<Int> =
        context.settingsDataStore.data.map { it[Keys.COMPACTION_THRESHOLD_PERCENT] ?: 75 }

    val compactionKeepRecentTurns: Flow<Int> =
        context.settingsDataStore.data.map { it[Keys.COMPACTION_KEEP_RECENT_TURNS] ?: 6 }

    val compactionNotify: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.COMPACTION_NOTIFY] ?: true }

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

    suspend fun setCompactionEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.COMPACTION_ENABLED] = enabled }
    }

    suspend fun setCompactionThresholdPercent(value: Int) {
        context.settingsDataStore.edit { it[Keys.COMPACTION_THRESHOLD_PERCENT] = value.coerceIn(50, 95) }
    }

    suspend fun setCompactionKeepRecentTurns(value: Int) {
        context.settingsDataStore.edit { it[Keys.COMPACTION_KEEP_RECENT_TURNS] = value.coerceIn(2, 20) }
    }

    suspend fun setCompactionNotify(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.COMPACTION_NOTIFY] = enabled }
    }
}
