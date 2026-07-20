package com.legion.orion.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.liquidGlass
import com.legion.orion.feature.engine.GodotEmbedPanel
import com.legion.orion.runtime.GodotProcessState
import com.legion.orion.runtime.GodotPresentation

/**
 * The user's explicit ask: agentic Godot game-dev output visible "in a small window in the chat
 * itself" — not a `DevTool` tool-strip tab (the codebase's existing convention for live surfaces
 * like Preview). Deliberately new UI infrastructure with no prior precedent here, per that request.
 *
 * State-driven, not manually opened: mounted only while [state] is [GodotProcessState.Running] —
 * i.e. only once an agent (or the user via the Engine debug screen) has actually called
 * `godot_launch`/[com.legion.orion.runtime.GodotProcessController.ensureRunning] — and fully
 * unmounted otherwise, matching "zero footprint when idle" for the embedded engine generally.
 *
 * **No fullscreen-expand affordance** — deliberately dropped after two on-device dead ends: (1) a
 * second concurrent `GodotEmbedPanel` for a separate fullscreen overlay raced the small card's for
 * the same `:godot` process's single `GodotService` rendering-surface handoff (one got stuck at
 * the wrong size); (2) relaunching the full-screen `GodotHostActivity` (G1) against an
 * already-running engine instead **crashed the whole `:godot` process** (`appDied` — no safe
 * in-process reinit); (3) resizing this SAME panel instance in place avoided both crashes, but
 * Godot's `RemoteGodotFragment` doesn't rescale its rendered content to a growing container
 * (aspect-locked/letterboxed at whatever size it was first attached at), AND the full-bounds
 * `SurfaceView` it creates intercepts touch ahead of an overlaid "exit fullscreen" button, making
 * exit unreachable. All three are Godot-AAR-internal constraints, not fixable from this side
 * without patching the vendored engine. The small docked card alone still delivers the actual ask —
 * live agentic output visible in the chat screen — reliably.
 */
@Composable
fun GodotChatCard(
    state: GodotProcessState,
    projectPath: String?,
    presentation: GodotPresentation,
    onOpenEditor: () -> Unit,
    onExpand: () -> Unit,
    onReturnToChat: () -> Unit,
    onStop: () -> Unit,
    previewHeight: Dp = 200.dp,
    temporarilyCollapsed: Boolean = false,
) {
    if (state !is GodotProcessState.Running) return
    if (projectPath == null) return
    var previewExpanded by rememberSaveable(projectPath) { mutableStateOf(true) }
    val contentVisible = previewExpanded && !temporarilyCollapsed
    val embeddedHeight by animateDpAsState(
        targetValue = if (contentVisible) previewHeight else 0.dp,
        animationSpec = tween(durationMillis = 220),
        label = "Godot preview height",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .liquidGlass(shape = RoundedCornerShape(16.dp), fillTopAlpha = 0.07f),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { previewExpanded = !previewExpanded }
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.SportsEsports, null, tint = Cosmos.Violet3, modifier = Modifier.height(16.dp))
            Text(
                if (presentation == GodotPresentation.FULLSCREEN) "Godot · fullscreen" else "Godot engine",
                style = MaterialTheme.typography.labelMedium,
                color = Cosmos.TextMuted,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (contentVisible) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                if (contentVisible) "Collapse Godot preview" else "Expand Godot preview",
                tint = Cosmos.TextMuted,
            )
            IconButton(onClick = onOpenEditor) {
                Icon(Icons.Outlined.Code, "Open full Godot editor", tint = Cosmos.TextMuted)
            }
            IconButton(onClick = if (presentation == GodotPresentation.FULLSCREEN) onReturnToChat else onExpand) {
                Icon(
                    if (presentation == GodotPresentation.FULLSCREEN) Icons.Outlined.CloseFullscreen else Icons.Outlined.Fullscreen,
                    if (presentation == GodotPresentation.FULLSCREEN) "Return preview to chat" else "Expand fullscreen",
                    tint = Cosmos.Violet3,
                )
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Outlined.Stop, "Stop Godot", tint = Cosmos.TextMuted)
            }
        }
        if (presentation == GodotPresentation.EMBEDDED) {
            // Keep the same fragment/surface mounted while collapsed. Godot cannot safely initialize
            // a second fragment in an already-running engine process, so AnimatedVisibility (which
            // disposes and recreates content) is unsafe here. A clipped height animation gives the
            // requested collapse behavior without restarting or duplicating the engine host.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(embeddedHeight)
                    .clipToBounds()
                    .padding(horizontal = 8.dp),
            ) {
                GodotEmbedPanel(projectPath, modifier = Modifier.fillMaxSize())
            }
        } else {
            AnimatedVisibility(
                visible = contentVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Text(
                    "The game is running in the full-screen Godot window. Tap the fullscreen button to restart it safely inside chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Cosmos.TextMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}
