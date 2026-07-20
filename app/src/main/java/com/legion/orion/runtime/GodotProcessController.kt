package com.legion.orion.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.legion.orion.runtime.godot.GodotEmbedService
import com.legion.orion.runtime.godot.GodotLaunch
import com.legion.orion.runtime.godot.GodotProjectInstaller
import com.legion.orion.runtime.godot.GodotRendererPolicy
import com.legion.orion.runtime.godot.GodotPreviewProject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface GodotProcessState {
    data object Idle : GodotProcessState
    data object Starting : GodotProcessState
    data object Running : GodotProcessState
    data object Stopping : GodotProcessState
}

enum class GodotPresentation { EMBEDDED, FULLSCREEN }

/**
 * Main-process lifecycle owner for the :godot process (see runtime/godot's manifest). Godot has no
 * safe in-process teardown/reinit — its core holds extensive static/global state, confirmed by a
 * Godot co-founder reviewing a failed iOS reinit attempt (godotengine/godot#99705) — so "stop" here
 * is a real process kill, not a hope that engine/GC cleanup behaves. That's also what delivers zero
 * incremental footprint when Godot isn't in use: the :godot process simply doesn't exist between
 * [ensureRunning] calls.
 *
 * Modeled on [NodeController]'s start/health-poll shape, with process-alive detection standing in
 * for Node's HTTP health check — no control channel into the running engine exists yet (that
 * arrives in a later phase alongside the GDExtension bridge), so there is nothing to health-check
 * beyond "did the OS process come up." There is also no death-*detection* once running (only
 * explicit [teardown] transitions state back to Idle) — confirmed on-device: if the engine dies on
 * its own (its native init failure alert calls its own `destroyAndKillProcess`), this controller's
 * state is left stale until the next [teardown]/[ensureRunning] call reconciles it against
 * [isProcessAlive]. Fixing that needs the bound-service death-detection hook planned for the
 * embedding phase (G2) — not worth a bespoke poll loop just for this one case.
 *
 * **Resolved 2026-07-20:** the vendored `template_release` AAR (see runtime/godot/repo/README.md) is
 * now a custom SCons build (`disable_path_overrides=no`), not the stock GitHub release — confirmed
 * on-device (Honor Magic 7 Pro) via byte-level diff of the compiled `.so` (zero occurrences of the
 * "compiled without support for path overrides" string that the old stock binary had 5 of). So
 * [ensureRunning] *can* now point the engine at an arbitrary project directory via `--path`; it still
 * defaults to the bundled `src/main/assets/` project until the caller threads a real project
 * directory through (not yet wired — see the AI-control bridge/G4+ work), which is a Kotlin-side
 * plumbing gap now, not an engine-binary limitation.
 */
@Singleton
class GodotProcessController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val arbiter: HeavyWorkArbiter,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow<GodotProcessState>(GodotProcessState.Idle)
    val state: StateFlow<GodotProcessState> = _state.asStateFlow()

    private var currentProjectDir: File? = null
    private val _projectPath = MutableStateFlow<String?>(null)
    val projectPath: StateFlow<String?> = _projectPath.asStateFlow()
    private val _presentation = MutableStateFlow(GodotPresentation.EMBEDDED)
    val presentation: StateFlow<GodotPresentation> = _presentation.asStateFlow()

    /**
     * **Confirmed on-device: do NOT do this.** Starting a *second* `GodotHostActivity` Intent
     * against an already-running `:godot` process (e.g. to bring a full-screen view back after the
     * original G1 activity was destroyed by back-navigation) crashes the whole `:godot` process
     * (`ActivityTaskManager: Force finishing activity ... reason appDied`) — the same "no safe
     * in-process reinit" constraint documented at this class's top, just triggered by a second
     * Activity attach instead of a stop/restart. A G2 Compose-embedded panel's Fragment must stay
     * the SAME instance across a small↔fullscreen size change (resize its Modifier in place) rather
     * than disposing and recreating it for the same reason applied to embeds, not just Activities —
     * see `GodotChatCard`'s kdoc.
     */

    /**
     * Starts (if needed) and shows the full-screen Godot host. With [projectDir] null (the common
     * case — AI-driven game generation targets this module's bundled `src/main/assets/` project,
     * since Android's W^X policy blocks dlopen of any `.so` outside the APK's own jniLibs-extracted
     * path, so an arbitrary external project directory can never host its own loadable GDExtension
     * bridge `.so`), boots the bundled default project. With [projectDir] set (the editor-port /
     * "open a project synced from PC" use case, where no bridge `.so` needs to load from inside that
     * directory), passes `--path <projectDir>` — now supported since the engine binary vendored in
     * runtime/godot/repo is a custom build with `disable_path_overrides=no` (see repo/README.md).
     *
     * Only one project may be loaded at a time (Godot has no safe in-process reinit — see this
     * class's top-level kdoc). Calling this with a *different* [projectDir] while already running
     * refuses rather than silently ignoring the request; call [teardown] first to switch projects.
     *
     * [showFullscreen] gates the G1 full-screen `GodotHostActivity` launch specifically — the AI
     * tool path (`godot_launch`, both `GodotAgentTools` and `ClaudeToolBridge`) passes false so
     * starting the engine for a chat-driven game-dev session doesn't yank the user away from the
     * chat screen to a full-screen view; the small G2 chat-embedded panel (`GodotChatCard`) picks up
     * rendering once this returns and `state` becomes [GodotProcessState.Running], independent of
     * whether the G1 activity was ever shown. The Engine debug screen's "Launch" button keeps the
     * default `true` (that button's whole point IS to demonstrate G1).
     */
    suspend fun ensureRunning(projectDir: File? = null, showFullscreen: Boolean = true): Boolean = lock.withLock {
        val resolvedProjectDir = GodotProjectInstaller.prepare(
            context,
            projectDir ?: GodotPreviewProject.ensureSeeded(context),
        ).canonicalFile
        if (_state.value is GodotProcessState.Running) {
            if (isProcessAlive()) {
                if (currentProjectDir?.absolutePath != resolvedProjectDir.absolutePath) {
                    Log.w(
                        TAG,
                        "refusing to switch projects while :godot is running " +
                            "(current=$currentProjectDir, requested=$resolvedProjectDir); call teardown() first",
                    )
                    return@withLock false
                }
                if (showFullscreen && _presentation.value != GodotPresentation.FULLSCREEN) {
                    Log.w(TAG, "fullscreen requires a clean engine restart; use restartFullscreen()")
                    return@withLock false
                }
                return@withLock true
            }
            _state.value = GodotProcessState.Idle // process died without us noticing; fall through and restart
            currentProjectDir = null
        }

        _state.value = GodotProcessState.Starting
        arbiter.exclusive(HeavyWorkArbiter.Subsystem.GODOT) {
            context.startService(Intent(context, GodotEmbedService::class.java))
        }

        if (!waitUntil(maxTries = 20, delayMs = 500) { isProcessAlive() }) {
            Log.w(TAG, ":godot process did not start in time")
            _state.value = GodotProcessState.Idle
            return@withLock false
        }

        if (showFullscreen) {
            val args = GodotRendererPolicy.commandLineArgsFor(GodotRendererPolicy.tierFor()) +
                listOf("--path", resolvedProjectDir.absolutePath)
            context.startActivity(GodotLaunch.intentFor(context, args))
        }
        currentProjectDir = resolvedProjectDir
        _projectPath.value = resolvedProjectDir.absolutePath
        _presentation.value = if (showFullscreen) GodotPresentation.FULLSCREEN else GodotPresentation.EMBEDDED
        _state.value = GodotProcessState.Running
        true
    }

    /** Safely changes hosting surfaces by killing the engine and starting a fresh process. */
    suspend fun restart(projectDir: File, fullscreen: Boolean): Boolean {
        teardown()
        return ensureRunning(projectDir, showFullscreen = fullscreen)
    }

    /** Idempotent high-level entry point that also switches project/presentation when required. */
    suspend fun startForProject(projectDir: File, fullscreen: Boolean): Boolean {
        val requested = projectDir.canonicalPath
        val expectedPresentation = if (fullscreen) GodotPresentation.FULLSCREEN else GodotPresentation.EMBEDDED
        val alreadyMatching = _state.value is GodotProcessState.Running &&
            _projectPath.value == requested && _presentation.value == expectedPresentation
        return if (alreadyMatching) ensureRunning(projectDir, fullscreen) else {
            if (_state.value is GodotProcessState.Running || isProcessAlive()) teardown()
            ensureRunning(projectDir, fullscreen)
        }
    }

    /** Hard-kills the :godot process. Real teardown, not just finishing the activity. */
    suspend fun teardown(): Boolean = lock.withLock {
        // State can be stale after the fullscreen Activity exits on its own. Never start a fresh
        // :godot process merely to deliver ACTION_QUIT to an engine that is already gone.
        if (!isProcessAlive()) {
            _state.value = GodotProcessState.Idle
            currentProjectDir = null
            _projectPath.value = null
            return@withLock true
        }
        _state.value = GodotProcessState.Stopping
        context.startService(Intent(context, GodotEmbedService::class.java).setAction(GodotEmbedService.ACTION_QUIT))

        var dead = waitUntil(maxTries = 10, delayMs = 300) { !isProcessAlive() }
        // startService(ACTION_QUIT) can race process death: Android may create a short-lived
        // replacement process solely to deliver that queued intent. Require the process to remain
        // dead beyond the service's 200 ms delayed kill before a caller is allowed to restart it.
        if (dead) {
            delay(600)
            dead = !isProcessAlive()
            if (!dead) {
                dead = waitUntil(maxTries = 5, delayMs = 300) { !isProcessAlive() }
                if (dead) delay(300)
                dead = dead && !isProcessAlive()
            }
        }
        _state.value = GodotProcessState.Idle
        currentProjectDir = null
        _projectPath.value = null
        _presentation.value = GodotPresentation.EMBEDDED
        if (!dead) Log.w(TAG, ":godot process did not die in time after the quit signal")
        dead
    }

    fun isProcessAlive(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val godotProcessName = "${context.packageName}:godot"
        return am.runningAppProcesses.orEmpty().any { it.processName == godotProcessName }
    }

    private suspend fun waitUntil(maxTries: Int, delayMs: Long, condition: () -> Boolean): Boolean {
        repeat(maxTries) {
            if (condition()) return true
            delay(delayMs)
        }
        return condition()
    }

    private companion object {
        const val TAG = "GodotProcessController"
    }
}
