package com.legion.orion.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark-first palette: deep neutral surfaces, electric accent. Tuned for OLED.
private val OrionDarkColors = darkColorScheme(
    primary = Color(0xFF7C9CFF),
    onPrimary = Color(0xFF0A1029),
    primaryContainer = Color(0xFF2A3A6B),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF60E1C8),
    onSecondary = Color(0xFF003329),
    secondaryContainer = Color(0xFF0F4A3D),
    onSecondaryContainer = Color(0xFFB8FFEC),
    tertiary = Color(0xFFFFB870),
    onTertiary = Color(0xFF3A2200),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE3E5EC),
    surface = Color(0xFF0F1218),
    onSurface = Color(0xFFE3E5EC),
    surfaceVariant = Color(0xFF1A1E27),
    onSurfaceVariant = Color(0xFFA9AFBF),
    surfaceContainer = Color(0xFF141821),
    surfaceContainerHigh = Color(0xFF1B2029),
    surfaceContainerHighest = Color(0xFF232934),
    outline = Color(0xFF3A4150),
    outlineVariant = Color(0xFF262C38),
    error = Color(0xFFFF6E6E),
    onError = Color(0xFF330B0B),
)

private val OrionLightColors = lightColorScheme(
    primary = Color(0xFF3D5CCC),
    onPrimary = Color.White,
    background = Color(0xFFF7F8FC),
    surface = Color.White,
)

@Composable
fun OrionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) OrionDarkColors else OrionLightColors,
        typography = OrionTypography,
        content = content,
    )
}
