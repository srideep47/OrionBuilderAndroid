package com.legion.orion.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** Top-level destinations — mirrors the desktop Orion Builder sidebar. */
enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    CHAT("chat", "Chat", Icons.AutoMirrored.Outlined.Chat),
    PROJECTS("projects", "Build", Icons.Outlined.GridView),
    ENGINE("engine", "Engine", Icons.Outlined.Bolt),
    MODELS("models", "Models", Icons.Outlined.Layers),
    NETWORK("network", "Hub", Icons.Outlined.Hub),
    MEDIA("media", "Media", Icons.Outlined.AutoAwesome),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings),
}
