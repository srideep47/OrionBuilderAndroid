package com.legion.orion.feature.engine

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import com.legion.orion.runtime.godot.GodotEmbedFragmentHost
import java.io.File

/**
 * G2 debug entry point: hosts Godot's own `RemoteGodotFragment` (via [GodotEmbedFragmentHost])
 * inline in a Compose panel, rendering in the isolated `:godot` process while living inside
 * Orion's own UI — the Compose-embedded alternative to G1's full-screen `GodotHostActivity` task.
 *
 * Not the real per-project `DevTool.GAME` surface yet (a later phase); this proves the embedding
 * mechanism on real hardware. Don't open this at the same time as G1's full-screen debug card —
 * both share the single Godot instance the `:godot` process supports.
 *
 * Sizing is entirely the caller's responsibility via [modifier] — this previously hardcoded
 * `.height(320.dp)` *after* the incoming modifier, which silently clamped the panel to a fixed
 * height no matter what the caller asked for (surfaced when reusing this for a true fullscreen
 * presentation, `GodotFullscreenOverlay`, which passed `fillMaxSize()` and still rendered small).
 */
@Composable
fun GodotEmbedPanel(projectPath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val fragmentActivity = context as? FragmentActivity ?: return
    val containerId = remember { View.generateViewId() }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> FragmentContainerView(ctx).apply { id = containerId } },
    )

    DisposableEffect(Unit) {
        val fm = fragmentActivity.supportFragmentManager
        val fragment = GodotEmbedFragmentHost.newFragmentInstance()
        fm.beginTransaction().replace(containerId, fragment).commitNowAllowingStateLoss()
        GodotEmbedFragmentHost.start(fragment, File(projectPath))
        onDispose {
            GodotEmbedFragmentHost.stop(fragment)
            runCatching { fm.beginTransaction().remove(fragment).commitNowAllowingStateLoss() }
        }
    }
}
