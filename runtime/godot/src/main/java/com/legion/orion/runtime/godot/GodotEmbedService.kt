package com.legion.orion.runtime.godot

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process

/**
 * Marker/lifecycle service for the :godot process. Its only job today is giving the main process
 * something to start (which brings the :godot process up on demand — see
 * [com.legion.orion.runtime.GodotProcessController.ensureRunning]) and a channel to request a hard
 * kill ([com.legion.orion.runtime.GodotProcessController.teardown]).
 *
 * There is no engine control channel into this process yet (that arrives with the GDExtension
 * bridge in a later phase), so [ACTION_QUIT] is an unconditional process kill, not a graceful
 * `SceneTree.quit()` — safe today since this phase has no meaningful running-game state to flush,
 * and worth revisiting once the bridge can signal the engine directly first.
 */
class GodotEmbedService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            // Give this onStartCommand call a moment to actually return to its caller
            // (GodotProcessController's startService call) before the process disappears.
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, QUIT_GRACE_MS)
        }
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_QUIT = "com.legion.orion.runtime.godot.action.QUIT"
        private const val QUIT_GRACE_MS = 200L
    }
}
