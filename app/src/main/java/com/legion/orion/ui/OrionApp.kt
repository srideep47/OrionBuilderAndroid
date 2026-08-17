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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.legion.orion.OrionApplication
import com.legion.orion.core.design.CosmicBackground
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.Liquid
import com.legion.orion.core.design.LocalBottomChromeHeight
import com.legion.orion.core.design.LocalHazeState
import com.legion.orion.core.design.liquidGlass
import com.legion.orion.core.design.rememberHaptic
import com.legion.orion.feature.code.CodeScreen
import com.legion.orion.feature.hub.HubScreen
import com.legion.orion.feature.models.ModelsScreen
import com.legion.orion.feature.settings.SettingsScreen
import com.legion.orion.runtime.RuntimeBootstrap
import dagger.hilt.android.EntryPointAccessors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

@Composable
fun OrionApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDest = backStack?.destination
    val hazeState = remember { HazeState() }
    val context = LocalContext.current
    val bootstrap = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            OrionApplication.BridgeEntryPoint::class.java,
        ).runtimeBootstrap()
    }
    val boot by bootstrap.state.collectAsState()

    // Floating bar: icon + persistent label, bottom margin, and breathing room.
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val chromeHeight = 62.dp + 14.dp + 8.dp + navBarInset

    CompositionLocalProvider(
        LocalHazeState provides hazeState,
        LocalBottomChromeHeight provides chromeHeight,
    ) {
        Box(Modifier.fillMaxSize()) {
            CosmicBackground()

            NavHost(
                navController = navController,
                startDestination = TopDestination.CODE.route,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState),
            ) {
                composable(TopDestination.CODE.route) { CodeScreen() }
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
                    .widthIn(max = 560.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 14.dp),
            )

            // Full-screen first-run setup — auto, no Install buttons. Dismisses when Ready.
            if (boot.busy || (boot.phase == RuntimeBootstrap.Phase.Failed && !boot.linuxReady)) {
                RuntimeBootstrapOverlay(boot)
            }
        }
    }
}

@Composable
private fun RuntimeBootstrapOverlay(boot: RuntimeBootstrap.State) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE604030A))
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .liquidGlass(shape = Liquid.ShapeMedium, fillTopAlpha = 0.14f, floating = true)
                .padding(horizontal = 22.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Setting up Orion",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Cosmos.Text,
            )
            Text(
                "Installing Linux, Claude Code, Codex, Grok Build, Android build tools, and Node — " +
                    "automatically, one time. Keep the app open and online for downloads.",
                style = MaterialTheme.typography.bodySmall,
                color = Cosmos.TextMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            if (boot.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = Cosmos.Violet,
                    strokeWidth = 3.dp,
                )
            }
            Text(
                boot.message.ifBlank { "Working…" },
                style = MaterialTheme.typography.labelLarge,
                color = Cosmos.Violet3,
                textAlign = TextAlign.Center,
            )
            if (boot.detail.isNotBlank()) {
                Text(
                    boot.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextFaint,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(4.dp))
            BootstrapCheck("Linux + Claude", boot.linuxReady || boot.claudeReady)
            BootstrapCheck("Codex CLI", boot.codexReady)
            BootstrapCheck("Grok Build", boot.grokReady)
            BootstrapCheck("Android build tools", boot.buildToolsReady)
            BootstrapCheck("Node tooling", boot.nodeReady)
            boot.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Cosmos.Red, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun BootstrapCheck(label: String, ready: Boolean) {
    Text(
        "${if (ready) "✓" else "·"}  $label",
        style = MaterialTheme.typography.labelSmall,
        color = if (ready) Cosmos.Text else Cosmos.TextFaint,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * iOS 26 floating Liquid Glass tab bar: a blurred capsule hovering over content.
 * Every destination keeps its label visible so the shell remains self-explanatory; the selected
 * tab receives the violet lens. Real backdrop blur via haze on Android 12+.
 */
@Composable
private fun LiquidTabBar(
    current: androidx.navigation.NavDestination?,
    onNavigate: (TopDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
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
            TabItem(
                dest = dest,
                selected = selected,
                onClick = { onNavigate(dest) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TabItem(dest: TopDestination, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val lens by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.7f),
        label = "lens",
    )

    Column(
        modifier
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
            .padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
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
        Text(
            dest.label,
            color = if (selected) Color.White else Cosmos.TextMuted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}
