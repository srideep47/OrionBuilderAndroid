package com.legion.orion.core.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cosmos color scheme — deep-space violet glassmorphism. Dark-only by design,
// mirroring the desktop Orion Builder. Surfaces are intentionally translucent so
// the animated CosmicBackground shows through (the "liquid glass" effect).
private val CosmosScheme = darkColorScheme(
    primary = Cosmos.Violet,
    onPrimary = Color(0xFF11082B),
    primaryContainer = Cosmos.Violet2,
    onPrimaryContainer = Cosmos.Violet3,
    secondary = Cosmos.Green,
    onSecondary = Color(0xFF00271C),
    secondaryContainer = Color(0xFF0F4A3D),
    onSecondaryContainer = Color(0xFFB8FFEC),
    tertiary = Cosmos.Amber,
    onTertiary = Color(0xFF3A2200),
    background = Cosmos.Bg,
    onBackground = Cosmos.Text,
    surface = Color(0x4D08041A),
    onSurface = Cosmos.Text,
    surfaceVariant = Cosmos.GlassFillStrong,
    onSurfaceVariant = Cosmos.TextMuted,
    surfaceContainerLowest = Color(0xCC04030A),
    surfaceContainerLow = Color(0x9908041A),
    surfaceContainer = Color(0x6608041A),
    surfaceContainerHigh = Color(0x14FFFFFF),
    surfaceContainerHighest = Color(0x1FFFFFFF),
    outline = Color(0x3AFFFFFF),
    outlineVariant = Cosmos.Hairline,
    error = Cosmos.Red,
    onError = Color(0xFF330B0B),
    errorContainer = Color(0x33FF505A),
    onErrorContainer = Cosmos.Red,
    inverseSurface = Cosmos.Text,
    inverseOnSurface = Cosmos.Bg,
)

@Composable
fun OrionTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = CosmosScheme,
        typography = OrionTypography,
        content = content,
    )
}
