package com.legion.orion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.CosmicBackground
import com.legion.orion.feature.chat.ChatScreen
import com.legion.orion.feature.engine.EngineScreen
import com.legion.orion.feature.media.MediaScreen
import com.legion.orion.feature.models.ModelsScreen
import com.legion.orion.feature.network.NetworkScreen
import com.legion.orion.feature.settings.SettingsScreen
import com.legion.orion.feature.workspace.BuildWorkspace

@Composable
fun OrionApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDest = backStack?.destination

    Box(Modifier.fillMaxSize()) {
        CosmicBackground()

        Column(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = TopDestination.CHAT.route,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                composable(TopDestination.CHAT.route) { ChatScreen() }
                composable(TopDestination.PROJECTS.route) { BuildWorkspace() }
                composable(TopDestination.ENGINE.route) { EngineScreen() }
                composable(TopDestination.MODELS.route) { ModelsScreen() }
                composable(TopDestination.NETWORK.route) { NetworkScreen() }
                composable(TopDestination.MEDIA.route) { MediaScreen() }
                composable(TopDestination.SETTINGS.route) { SettingsScreen() }
            }

            CosmosBottomBar(
                current = currentDest,
                onNavigate = { dest ->
                    navController.navigate(dest.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
    }
}

/**
 * iOS 26 / macOS 26 "Liquid Glass" tab bar: a floating glass pill. The selected
 * tab expands into an icon+label pill; the rest are icon-only. Sits over the
 * animated cosmos so the backdrop shows through.
 */
@Composable
private fun CosmosBottomBar(
    current: androidx.navigation.NavDestination?,
    onNavigate: (TopDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xCC04030A))
            .border(width = 0.5.dp, color = Cosmos.HairlineSoft, shape = RoundedCornerShape(0.dp))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopDestination.entries.forEach { dest ->
            val selected = current?.hierarchy?.any { it.route == dest.route } == true
            TabPill(dest = dest, selected = selected, onClick = { onNavigate(dest) })
        }
    }
}

@Composable
private fun TabPill(dest: TopDestination, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val bg = if (selected) Cosmos.PrimaryGradient
    else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    val hPad by animateDpAsState(if (selected) 12.dp else 11.dp, label = "pad")

    Row(
        Modifier
            .clip(shape)
            .background(bg, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = hPad, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            dest.icon,
            contentDescription = dest.label,
            tint = if (selected) Color.White else Cosmos.TextMuted,
        )
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Text(
                dest.label,
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            )
        }
    }
}
