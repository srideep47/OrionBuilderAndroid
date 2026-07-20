package com.legion.orion.runtime

import android.app.ActivityManager
import android.content.Context
import com.legion.orion.inference.InferenceController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Generalizes the one-off "unload the LLM before this heavy operation" dance
 * [com.legion.orion.build.ApkBuildController] hand-rolls for Gradle builds (see
 * `buildAndInstall`'s "Unloading model to free memory for the build…" step) into a shared gate any
 * heavy resident subsystem can route through. There was no cross-engine resource gate anywhere in
 * this app before this — every existing safeguard was scoped to serializing calls within its own
 * single subsystem, not brokering shared device RAM. Adding Godot as another heavy resident without
 * this would be one more one-off unload dance in an already-growing pile of them.
 *
 * On the low-RAM tier this proactively frees other registered heavy subsystems before granting
 * exclusive access; on a high-RAM device (the 12GB Honor Magic 7 Pro) it's close to a no-op. Scoped
 * today to the subsystem that actually contends for RAM with Godot (the on-device LLM) — Node and
 * the Claude Code proot process aren't covered yet; extend as more subsystems need it.
 */
@Singleton
class HeavyWorkArbiter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inference: InferenceController,
) {
    private val mutex = Mutex()

    private val isLowRamTier: Boolean by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem < LOW_RAM_THRESHOLD_BYTES
    }

    /**
     * Runs [block] with other registered heavy subsystems unloaded first, if this device is on the
     * low-RAM tier. Callers own reloading their own subsystem afterward, same as
     * `ApkBuildController.buildAndInstall` already does for the LLM today — this does not restore
     * anything automatically.
     */
    suspend fun <T> exclusive(subsystem: Subsystem, block: suspend () -> T): T = mutex.withLock {
        if (isLowRamTier && subsystem != Subsystem.INFERENCE && inference.isLoaded) {
            runCatching { inference.unload() }
        }
        block()
    }

    enum class Subsystem { INFERENCE, GODOT, BUILD }

    private companion object {
        // Below this, proactively free other heavy subsystems (roughly the 6-8GB device class);
        // the 12GB Honor Magic 7 Pro tier is comfortably above it.
        const val LOW_RAM_THRESHOLD_BYTES = 8L * 1024 * 1024 * 1024
    }
}
