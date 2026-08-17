package com.legion.orion.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val twinkle: Float)

/**
 * Animated deep-space backdrop — nebula + twinkling starfield.
 *
 * The nebula is a static Canvas (no snapshot state), so Compose draws it once and keeps the layer.
 * Stars twinkle at ~12 fps only while the Activity is RESUMED. Visually the same 7s period as
 * before; the old 60s nebula swirl was ±3% of the screen and is not perceptible against the stars.
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

    var twinkleT by remember { mutableFloatStateOf(0f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                delay(80L)
                val next = twinkleT + (0.080f / 7f) * 6.28f
                twinkleT = if (next > 6.28f) next - 6.28f else next
            }
        }
    }

    Box(modifier.fillMaxSize().background(Cosmos.Bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0x59A878FF), Color.Transparent),
                    center = Offset(w * 0.22f, h * 0.28f),
                    radius = w * 0.6f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0x475A8CFF), Color.Transparent),
                    center = Offset(w * 0.80f, h * 0.72f),
                    radius = w * 0.65f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0x2EFFAAD2), Color.Transparent),
                    center = Offset(w * 0.52f, h * 0.5f),
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
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
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
