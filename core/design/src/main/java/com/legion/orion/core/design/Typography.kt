package com.legion.orion.core.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Bundled variable fonts matching the desktop trio (Inter / Space Grotesk /
// JetBrains Mono). Self-contained — no Play Services, no network. Each weight
// pins the variable `wght` axis via FontVariation so the variable TTF renders
// the right weight on API 29+.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun varFont(resId: Int, weight: FontWeight) = Font(
    resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val InterFamily: FontFamily = FontFamily(
    varFont(R.font.inter_variable, FontWeight.Normal),
    varFont(R.font.inter_variable, FontWeight.Medium),
    varFont(R.font.inter_variable, FontWeight.SemiBold),
    varFont(R.font.inter_variable, FontWeight.Bold),
)

/** Display / heading font — distinctive, used for page titles & big numbers. */
val DisplayFamily: FontFamily = FontFamily(
    varFont(R.font.space_grotesk_variable, FontWeight.Medium),
    varFont(R.font.space_grotesk_variable, FontWeight.SemiBold),
    varFont(R.font.space_grotesk_variable, FontWeight.Bold),
)

val MonoFamily: FontFamily = FontFamily(
    varFont(R.font.jetbrains_mono_variable, FontWeight.Normal),
    varFont(R.font.jetbrains_mono_variable, FontWeight.Medium),
)

val OrionTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.01).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.01).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)
