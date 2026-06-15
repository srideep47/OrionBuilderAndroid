package com.legion.orion.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level destinations. Five tabs — everything on the desktop maps into one
 * of these surfaces:
 *  - CHAT: unified command (chat + build agent + inline preview/code)
 *  - BUILD: workspace (editor, file tree, preview, problems, tools)
 *  - MODELS: engine + marketplace + local models + downloads
 *  - HUB: network peers + media jobs + publishing
 *  - SETTINGS: providers, workflow, engine defaults
 */
enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val activeIcon: ImageVector,
) {
    CHAT("chat", "Chat", Icons.AutoMirrored.Outlined.Chat, Icons.AutoMirrored.Filled.Chat),
    BUILD("build", "Build", Icons.Outlined.SpaceDashboard, Icons.Filled.SpaceDashboard),
    MODELS("models", "Models", Icons.Outlined.Layers, Icons.Filled.Layers),
    HUB("hub", "Hub", Icons.Outlined.Hub, Icons.Filled.Hub),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}
