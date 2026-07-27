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
        val MAX_AGENT_ITERATIONS = intPreferencesKey("max_agent_iterations")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val CLAUDE_CODE_MODEL = stringPreferencesKey("claude_code_model")
        val CLAUDE_CODE_EFFORT = stringPreferencesKey("claude_code_effort")
        val CLAUDE_CODE_PERMISSION_MODE = stringPreferencesKey("claude_code_permission_mode")
        val CODEX_MODEL = stringPreferencesKey("codex_model")
        val CODEX_EFFORT = stringPreferencesKey("codex_effort")
        val CODEX_SANDBOX = stringPreferencesKey("codex_sandbox")
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

    val maxAgentIterations: Flow<Int> =
        context.settingsDataStore.data.map { it[Keys.MAX_AGENT_ITERATIONS] ?: 12 }

    val hapticsEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.HAPTICS_ENABLED] ?: true }

    /** null = no `--model` flag passed, Claude Code uses the account's own default model. */
    val claudeCodeModel: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.CLAUDE_CODE_MODEL] }

    val claudeCodeEffort: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.CLAUDE_CODE_EFFORT] ?: "medium" }

    /** bypassPermissions, not acceptEdits: acceptEdits only auto-approves file edits, still blocking
     * Bash on "This command requires approval" with no one to ever answer it headlessly — confirmed
     * on-device this silently broke preview dev-server launches. */
    val claudeCodePermissionMode: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.CLAUDE_CODE_PERMISSION_MODE] ?: "bypassPermissions" }

    /** null = no `-m` flag passed, Codex uses whatever its own config.toml/account default is. */
    val codexModel: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.CODEX_MODEL] }

    /** Passed as `-c model_reasoning_effort=<value>`. Codex names these the same way Claude Code's
     * `--effort` does, so the two engines' option sheets can stay symmetrical. */
    val codexEffort: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.CODEX_EFFORT] ?: "medium" }

    /** `danger-full-access`, not `workspace-write`, and this is not the paranoid choice it sounds like.
     * Codex enforces `workspace-write` using Landlock + seccomp, kernel features that a proot chroot on
     * Android cannot be relied on to provide; when they're unavailable the sandbox fails closed and
     * every command dies. `danger-full-access` skips that enforcement path entirely and leaves file
     * access governed by the real Android app UID — the same reasoning that makes Claude Code's
     * `bypassPermissions` the correct default here, and the same reason proot must not pass `-0`.
     *
     * The name overstates the exposure in this context: "full access" means full access to what the
     * Orion app process could already reach on its own, not to the device at large. */
    val codexSandbox: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.CODEX_SANDBOX] ?: "danger-full-access" }

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

    suspend fun setMaxAgentIterations(value: Int) {
        context.settingsDataStore.edit { it[Keys.MAX_AGENT_ITERATIONS] = value.coerceIn(4, 40) }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.HAPTICS_ENABLED] = enabled }
    }

    suspend fun setClaudeCodeOptions(model: String?, effort: String, permissionMode: String) {
        context.settingsDataStore.edit {
            if (model != null) it[Keys.CLAUDE_CODE_MODEL] = model else it.remove(Keys.CLAUDE_CODE_MODEL)
            it[Keys.CLAUDE_CODE_EFFORT] = effort
            it[Keys.CLAUDE_CODE_PERMISSION_MODE] = permissionMode
        }
    }

    suspend fun setCodexOptions(model: String?, effort: String, sandbox: String) {
        context.settingsDataStore.edit {
            if (model != null) it[Keys.CODEX_MODEL] = model else it.remove(Keys.CODEX_MODEL)
            it[Keys.CODEX_EFFORT] = effort
            it[Keys.CODEX_SANDBOX] = sandbox
        }
    }
}
