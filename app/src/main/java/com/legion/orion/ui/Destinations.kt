package com.legion.orion.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level destinations:
 *  - MARTA: always-on assistant (voice + text) that drives Orion through AppControl APIs.
 *  - CODE: Claude / Grok / Codex / local build workspace.
 *  - MODELS: engine, marketplace, library, downloads.
 *  - HUB: peers, media, publishing.
 *  - SETTINGS: providers, agent workflow, app behavior.
 */
enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val activeIcon: ImageVector,
) {
    MARTA("marta", "Marta", Icons.Outlined.GraphicEq, Icons.Filled.GraphicEq),
    CODE("code", "Code", Icons.Outlined.Terminal, Icons.Filled.Terminal),
    MODELS("models", "Models", Icons.Outlined.Layers, Icons.Filled.Layers),
    HUB("hub", "Hub", Icons.Outlined.Hub, Icons.Filled.Hub),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}
