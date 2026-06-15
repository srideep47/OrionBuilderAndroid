package com.legion.orion.core.design

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Height of the floating bottom chrome (liquid tab bar + its margins), provided
 * by the app shell. Screens add this as bottom content padding so scrollables
 * flow underneath the glass bar but never end hidden behind it.
 */
val LocalBottomChromeHeight = compositionLocalOf<Dp> { 0.dp }
