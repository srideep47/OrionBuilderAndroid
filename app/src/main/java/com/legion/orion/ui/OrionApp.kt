package com.legion.orion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.legion.orion.core.design.CosmicBackground
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.Liquid
import com.legion.orion.core.design.LocalBottomChromeHeight
import com.legion.orion.core.design.LocalHazeState
import com.legion.orion.core.design.liquidGlass
import com.legion.orion.core.design.rememberHaptic
import com.legion.orion.feature.chat.ChatScreen
import com.legion.orion.feature.hub.HubScreen
import com.legion.orion.feature.models.ModelsScreen
import com.legion.orion.feature.settings.SettingsScreen
import com.legion.orion.feature.workspace.BuildWorkspace
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

@Composable
fun OrionApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDest = backStack?.destination
    val hazeState = remember { HazeState() }

    // Floating bar: ~56dp pill + 14dp bottom margin + 8dp breathing room.
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val chromeHeight = 56.dp + 14.dp + 8.dp + navBarInset

    CompositionLocalProvider(
        LocalHazeState provides hazeState,
        LocalBottomChromeHeight provides chromeHeight,
    ) {
        Box(Modifier.fillMaxSize()) {
            CosmicBackground()

            NavHost(
                navController = navController,
                startDestination = TopDestination.CHAT.route,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState),
            ) {
                composable(TopDestination.CHAT.route) { ChatScreen() }
                composable(TopDestination.BUILD.route) { BuildWorkspace() }
                composable(TopDestination.MODELS.route) { ModelsScreen() }
                composable(TopDestination.HUB.route) { HubScreen() }
                composable(TopDestination.SETTINGS.route) { SettingsScreen() }
            }

            LiquidTabBar(
                current = currentDest,
                onNavigate = { dest ->
                    navController.navigate(dest.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 14.dp),
            )
        }
    }
}

/**
 * iOS 26 floating Liquid Glass tab bar: a blurred capsule hovering over content.
 * The selected tab grows into an icon+label pill with a violet lens; others are
 * icon-only. Real backdrop blur via haze on Android 12+.
 */
@Composable
private fun LiquidTabBar(
    current: androidx.navigation.NavDestination?,
    onNavigate: (TopDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .liquidGlass(
                shape = Liquid.ShapePill,
                fillTopAlpha = 0.12f,
                fillBottomAlpha = 0.05f,
                specularStrength = 1.2f,
                floating = true,
            )
            .padding(horizontal = 7.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopDestination.entries.forEach { dest ->
            val selected = current?.hierarchy?.any { it.route == dest.route } == true
            TabItem(dest = dest, selected = selected, onClick = { onNavigate(dest) })
        }
    }
}

@Composable
private fun TabItem(dest: TopDestination, selected: Boolean, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val lens by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.7f),
        label = "lens",
    )

    Row(
        Modifier
            .clip(Liquid.ShapePill)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Cosmos.Violet.copy(alpha = lens),
                        Cosmos.Violet2.copy(alpha = lens),
                    ),
                ),
                Liquid.ShapePill,
            )
            .then(
                if (lens > 0.4f) {
                    Modifier.border(1.dp, Liquid.specular(1.3f * lens), Liquid.ShapePill)
                } else Modifier,
            )
            .clickable(interactionSource = interaction, indication = null) {
                if (!selected) haptic()
                onClick()
            }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (selected) dest.activeIcon else dest.icon,
            contentDescription = dest.label,
            tint = if (selected) Color.White else Cosmos.TextMuted,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    val s = 1f + 0.06f * lens
                    scaleX = s
                    scaleY = s
                },
        )
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Text(
                dest.label,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
