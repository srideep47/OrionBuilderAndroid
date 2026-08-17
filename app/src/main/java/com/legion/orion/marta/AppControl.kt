package com.legion.orion.marta

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.legion.orion.core.database.LocalModelDao
import com.legion.orion.core.datastore.SettingsRepository
import com.legion.orion.data.project.ActiveProjectStore
import com.legion.orion.data.project.ProjectManager
import com.legion.orion.engine.mnn.MnnBackend
import com.legion.orion.feature.chat.ChatMode
import com.legion.orion.inference.EngineState
import com.legion.orion.inference.InferenceController
import com.legion.orion.inference.InferenceService
import com.legion.orion.runtime.DevServerState
import com.legion.orion.runtime.GodotProcessController
import com.legion.orion.runtime.GodotProcessState
import com.legion.orion.ui.TopDestination
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * First-party API over Orion. Marta talks to this — never the Compose tree.
 */
@Singleton
class AppControl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inference: InferenceController,
    private val settings: SettingsRepository,
    private val projects: ProjectManager,
    private val activeProject: ActiveProjectStore,
    private val localModels: LocalModelDao,
    private val navigator: MartaNavigator,
    private val handoff: HandoffBus,
    private val devServer: DevServerState,
    private val godot: GodotProcessController,
) {
    data class WorldState(
        val engine: String,
        val model: String?,
        val backend: String?,
        val engineError: String?,
        val freeRamMb: Long,
        val totalRamMb: Long,
        val project: String?,
        val projectId: String?,
        val devServer: String,
        val godot: String,
        val models: List<String>,
        val soc: String,
    )

    suspend fun status(): WorldState {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val eng = inference.state.value
        val pid = activeProject.projectId.value
        val project = pid?.let { projects.get(it) }
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.ifBlank { Build.HARDWARE } else Build.HARDWARE
        return WorldState(
            engine = eng.status.name,
            model = eng.modelDir?.substringAfterLast('/'),
            backend = eng.backend?.configValue,
            engineError = eng.error,
            freeRamMb = mi.availMem / (1024 * 1024),
            totalRamMb = mi.totalMem / (1024 * 1024),
            project = project?.name,
            projectId = pid,
            devServer = devServer.current.value?.let { "running :${it.port}" } ?: "off",
            godot = when (godot.state.value) {
                GodotProcessState.Idle -> "idle"
                GodotProcessState.Starting -> "starting"
                GodotProcessState.Running -> "running"
                GodotProcessState.Stopping -> "stopping"
            },
            models = localModels.all().map { it.repoId ?: File(it.localPath).name },
            soc = soc,
        )
    }

    fun formatStatus(s: WorldState): String = buildString {
        appendLine("engine=${s.engine} model=${s.model ?: "none"} backend=${s.backend ?: "-"}")
        if (s.engineError != null) appendLine("engine_error=${s.engineError}")
        appendLine("ram=${s.freeRamMb}MB free / ${s.totalRamMb}MB  soc=${s.soc}")
        appendLine("project=${s.project ?: "none"}  dev_server=${s.devServer}  godot=${s.godot}")
        appendLine("models=${s.models.joinToString(", ").ifBlank { "none" }}")
        appendLine("brains=claude|grok|codex|build")
        appendLine("screens=marta|code|models|hub|settings")
    }

    fun navigate(target: String): String {
        val key = target.trim().lowercase()
        val dest = when (key) {
            "marta", "home" -> TopDestination.MARTA
            "code", "chat", "editor", "workspace" -> TopDestination.CODE
            "models", "engine", "library" -> TopDestination.MODELS
            "hub", "network", "peers", "media" -> TopDestination.HUB
            "settings", "settings" -> TopDestination.SETTINGS
            else -> return "Unknown screen '$target'. Use marta, code, models, hub, settings."
        }
        navigator.open(dest.route)
        return "Opened ${dest.label}"
    }

    suspend fun setSetting(key: String, value: String): String {
        val k = key.trim().lowercase()
        val v = value.trim()
        return when (k) {
            "haptics" -> { settings.setHapticsEnabled(v.toBooleanStrictOrNull() ?: return "haptics needs true/false"); "haptics=$v" }
            "compaction" -> { settings.setCompactionEnabled(v.toBooleanStrictOrNull() ?: return "compaction needs true/false"); "compaction=$v" }
            "compaction_notify" -> { settings.setCompactionNotify(v.toBooleanStrictOrNull() ?: return "needs true/false"); "compaction_notify=$v" }
            "compaction_threshold" -> { settings.setCompactionThresholdPercent(v.toIntOrNull() ?: return "needs 50-95"); "compaction_threshold=$v" }
            "keep_recent_turns" -> { settings.setCompactionKeepRecentTurns(v.toIntOrNull() ?: return "needs 2-20"); "keep_recent_turns=$v" }
            "max_agent_iterations" -> { settings.setMaxAgentIterations(v.toIntOrNull() ?: return "needs 4-40"); "max_agent_iterations=$v" }
            "theme_dark" -> { settings.setDarkTheme(v.toBooleanStrictOrNull() ?: return "needs true/false"); "theme_dark=$v" }
            else -> "Unknown setting '$key'. Known: haptics, compaction, compaction_notify, compaction_threshold, keep_recent_turns, max_agent_iterations, theme_dark"
        }
    }

    suspend fun loadMartaModel(voice: Boolean = false): String {
        val dir = martaModelDir() ?: return "Qwen3.5-2B-MNN is not on this phone yet. Put it in the models folder."
        InferenceService.start(context)
        inference.load(dir.absolutePath, MartaProfile.backend, MartaProfile.settings(voice))
        settings.setLastModel(dir.absolutePath, MartaProfile.backend.configValue)
        return "Loaded ${dir.name} on ${MartaProfile.backend.configValue}"
    }

    suspend fun loadNamedModel(name: String, backendName: String?): String {
        val needle = name.trim()
        val match = localModels.all().firstOrNull {
            it.repoId?.contains(needle, true) == true ||
                it.localPath.contains(needle, true) ||
                File(it.localPath).name.contains(needle, true)
        } ?: return "No local model matching '$name'"
        val backend = backendName?.let { raw ->
            MnnBackend.entries.firstOrNull { it.configValue.equals(raw, true) }
        } ?: MnnBackend.CPU
        InferenceService.start(context)
        val settingsTune = if (File(match.localPath).name.contains("2B", true)) {
            MartaProfile.settings()
        } else {
            MartaProfile.settings().copy(maxNewTokens = 512, enableThinking = false)
        }
        inference.load(match.localPath, backend, settingsTune)
        settings.setLastModel(match.localPath, backend.configValue)
        return "Loaded ${match.repoId ?: File(match.localPath).name} on ${backend.configValue}"
    }

    suspend fun unloadModel(): String {
        inference.unload()
        InferenceService.stop(context)
        return "Model unloaded"
    }

    suspend fun openProject(name: String): String {
        val hit = projects.observeProjects().first().firstOrNull { it.name.equals(name, true) || it.id == name }
            ?: return "No project named '$name'"
        activeProject.set(hit.id)
        navigator.open(TopDestination.CODE.route)
        return "Opened project ${hit.name}"
    }

    suspend fun listProjects(): String {
        val list = projects.observeProjects().first()
        if (list.isEmpty()) return "No projects yet"
        return list.joinToString("\n") { "${it.name}  (${it.stackType})" }
    }

    fun handoffToBrain(brain: String, brief: String): String {
        val key = brain.trim().lowercase()
        val allowed = setOf("claude", "grok", "codex", "build")
        if (key !in allowed) return "Unknown brain '$brain'. Use claude, grok, codex, or build."
        if (brief.isBlank()) return "Handoff needs a brief — what should the second brain do?"
        handoff.post(key, brief)
        navigator.open(TopDestination.CODE.route)
        return "Handed to $key. I opened Code so it can start."
    }

    fun engineReady(): Boolean = inference.state.value.status == EngineState.Status.READY

    fun martaModelDir(): File? {
        val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
        val dir = File(root, MartaProfile.MODEL_DIR_NAME)
        return dir.takeIf { File(it, "config.json").isFile }
    }

    fun chatModeForBrain(brain: String): ChatMode? = when (brain) {
        "claude" -> ChatMode.CLAUDE_CODE
        "grok" -> ChatMode.GROK
        "codex" -> ChatMode.CODEX
        "build" -> ChatMode.BUILD
        else -> null
    }
}
