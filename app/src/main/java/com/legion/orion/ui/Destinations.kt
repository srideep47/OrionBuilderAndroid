package com.legion.orion.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level destinations — four tabs, each a clearly-scoped surface:
 *  - CODE: the unified mobile Claude Code-style development space — conversation (Claude Code CLI /
 *    local model / Hub peer) plus file browser, editor, live preview, terminal, git, .env, and tasks.
 *    Replaces the old separate Chat and Workspace tabs, which overlapped heavily.
 *  - MODELS: engine, marketplace, local library, downloads.
 *  - HUB: network peers, media generation, shared media, publishing.
 *  - SETTINGS: providers, agent workflow, context compaction, app behavior.
 */
enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val activeIcon: ImageVector,
) {
    CODE("code", "Code", Icons.Outlined.Terminal, Icons.Filled.Terminal),
    MODELS("models", "Models", Icons.Outlined.Layers, Icons.Filled.Layers),
    HUB("hub", "Hub", Icons.Outlined.Hub, Icons.Filled.Hub),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}
