package com.legion.orion.runtime.godot

import androidx.fragment.app.Fragment
import java.io.File
import org.godotengine.godot.service.RemoteGodotFragment

/**
 * Facade over Godot's own [RemoteGodotFragment] (`org.godotengine.godot.service`) — Godot's
 * first-class answer to "render in a Compose-embedded panel while the engine runs in a separate
 * process." It pairs with `GodotService` (declared `android:process=":godot"` in this module's
 * manifest) over `Messenger` IPC, and hands the host a live `SurfaceControlViewHost` surface
 * package — this is exactly the mechanism this integration needed, already built upstream; nothing
 * here reimplements it.
 *
 * Exposed only through framework ([androidx.fragment.app.Fragment]) types so `:app` never needs
 * compile-time visibility of `org.godotengine.godot.*` itself — that AAR dependency stays
 * `implementation`-scoped to this module (see `build.gradle.kts`).
 */
object GodotEmbedFragmentHost {
    fun newFragmentInstance(): Fragment = RemoteGodotFragment()

    /**
     * Starts the remote game. Forces the OpenGL/Compatibility renderer regardless of
     * [GodotRendererPolicy]'s tier — Godot's own `GodotService` kdoc (as of 4.5+) documents that
     * Vulkan + the Swappy frame-pacing library crashes when hosted in a `Service` with no
     * `Activity` context, which is exactly this embedding path. The full-screen
     * `GodotHostActivity` path (G1) is unaffected and keeps using [GodotRendererPolicy]'s tier.
     */
    fun start(fragment: Fragment, projectDir: File) {
        val args = FORCE_OPENGL_ARGS + listOf("--path", projectDir.absolutePath)
        (fragment as RemoteGodotFragment).startRemoteGame(args.toTypedArray())
    }

    fun stop(fragment: Fragment) {
        (fragment as RemoteGodotFragment).stopRemoteGame()
    }

    private val FORCE_OPENGL_ARGS = listOf("--rendering-driver", "opengl3")
}
