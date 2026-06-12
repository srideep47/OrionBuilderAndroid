package com.legion.orion.core.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.sin
import kotlin.random.Random

private data class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val twinkle: Float)

/**
 * Animated deep-space backdrop — nebula swirl + twinkling starfield + drifting
 * grid. Mirrors the desktop `.cosmos` layer. Drawn once on a Canvas behind the
 * whole app; cheap enough for 120 Hz (a handful of radial gradients + points).
 */
@Composable
fun CosmicBackground(modifier: Modifier = Modifier) {
    val stars = remember {
        val rnd = Random(0xC05305)
        List(110) {
            Star(
                x = rnd.nextFloat(),
                y = rnd.nextFloat(),
                r = rnd.nextFloat() * 1.4f + 0.4f,
                phase = rnd.nextFloat() * 6.28f,
                twinkle = rnd.nextFloat() * 0.6f + 0.2f,
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "cosmos")
    val swirl by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(60_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "swirl",
    )
    val twinkleT by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "twinkle",
    )

    Box(modifier.fillMaxSize().background(Cosmos.Bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val dx = (swirl - 0.5f) * 0.06f * w
            val dy = (swirl - 0.5f) * 0.05f * h

            // Nebula clouds (radial gradients).
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0x59A878FF), Color.Transparent),
                    center = Offset(w * 0.22f + dx, h * 0.28f + dy),
                    radius = w * 0.6f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0x475A8CFF), Color.Transparent),
                    center = Offset(w * 0.80f - dx, h * 0.72f - dy),
                    radius = w * 0.65f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0x2EFFAAD2), Color.Transparent),
                    center = Offset(w * 0.52f, h * 0.5f + dy),
                    radius = w * 0.7f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0x3878D2FF), Color.Transparent),
                    center = Offset(w * 0.82f, h * 0.20f),
                    radius = w * 0.45f,
                ),
            )

            // Starfield with gentle twinkle.
            stars.forEach { s ->
                val a = (0.35f + 0.5f * (0.5f + 0.5f * sin(twinkleT + s.phase)) * s.twinkle).coerceIn(0f, 1f)
                drawCircle(
                    color = Color.White.copy(alpha = a),
                    radius = s.r,
                    center = Offset(s.x * w, s.y * h),
                )
            }
        }
    }
}
