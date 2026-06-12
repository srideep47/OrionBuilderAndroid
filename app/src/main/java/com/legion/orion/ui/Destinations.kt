package com.legion.orion.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** Top-level destinations shown in the bottom navigation bar. */
enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    CHAT("chat", "Chat", Icons.AutoMirrored.Outlined.Chat),
    PROJECTS("projects", "Build", Icons.Outlined.Folder),
    ENGINE("engine", "Engine", Icons.Outlined.Memory),
    NETWORK("network", "Network", Icons.Outlined.Hub),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings),
}
