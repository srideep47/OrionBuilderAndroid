package com.legion.orion.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Cosmos palette — ported 1:1 from the desktop Orion Builder design tokens
 * (orianbuilder-react globals.css). Deep-space violet, glassmorphism, neon accents.
 * This is the single source of truth for the "Liquid Glass / Cosmos" look that
 * mirrors the PC app and the iOS 26 / macOS 26 aesthetic.
 */
@Immutable
object Cosmos {
    // Backdrop
    val Bg = Color(0xFF04030A)
    val Deep = Color(0xFF08041A)
    val Black = Color(0xFF000000)

    // Accents
    val Violet = Color(0xFFA88CFF)
    val Violet2 = Color(0xFF6B4FD8)
    val Violet3 = Color(0xFFC5B3FF)
    val Blue = Color(0xFF78B4FF)
    val Pink = Color(0xFFFFA8D2)
    val Amber = Color(0xFFFFC56B)
    val Green = Color(0xFF5FDFA3)
    val Red = Color(0xFFFF8794)

    // Text on dark
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xCCFFFFFF) // ~0.8
    val TextMuted = Color(0x73FFFFFF) // ~0.45
    val TextFaint = Color(0x59FFFFFF) // ~0.35

    // Hairlines / surfaces
    val Hairline = Color(0x17FFFFFF) // ~0.09
    val HairlineSoft = Color(0x12FFFFFF) // ~0.07
    val GlassFill = Color(0x0AFFFFFF) // ~0.04
    val GlassFillStrong = Color(0x14FFFFFF) // ~0.08

    val VioletGlow = Color(0x2EA88CFF) // 0.18 alpha

    /** Primary action gradient: violet → deep violet (135deg). */
    val PrimaryGradient = Brush.linearGradient(listOf(Violet, Violet2))

    /** Title text gradient: white → light violet. */
    val TitleGradient = Brush.verticalGradient(listOf(Color.White, Violet3))

    /** Active nav highlight (90deg, fades to transparent). */
    val NavActiveGradient = Brush.horizontalGradient(listOf(Color(0x2EA88CFF), Color.Transparent))

    fun violetAlpha(a: Float) = Violet.copy(alpha = a)
}
