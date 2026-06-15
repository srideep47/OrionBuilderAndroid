package com.legion.orion.core.design

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.view.HapticFeedbackConstants
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Liquid Glass — iOS 26 / macOS 26 material language on top of the Cosmos palette.
 *
 * The material is built from four layers:
 *  1. backdrop blur (haze) when a [LocalHazeState] is provided and the surface floats
 *     over scrolling content;
 *  2. a vertical "lensing" fill (brighter at the top edge, like light entering glass);
 *  3. a specular border — a gradient stroke that catches light top-left;
 *  4. an inner top highlight.
 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

object Liquid {
    /** Continuous-feel corner radii. */
    val CornerLarge = 26.dp
    val CornerMedium = 20.dp
    val CornerSmall = 14.dp
    val CornerPill = 100.dp

    val ShapeLarge = RoundedCornerShape(CornerLarge)
    val ShapeMedium = RoundedCornerShape(CornerMedium)
    val ShapeSmall = RoundedCornerShape(CornerSmall)
    val ShapePill = RoundedCornerShape(CornerPill)

    /** Lensing fill: light enters at the top. */
    fun fill(alphaTop: Float = 0.10f, alphaBottom: Float = 0.03f, tint: Color = Color.White) =
        Brush.verticalGradient(listOf(tint.copy(alpha = alphaTop), tint.copy(alpha = alphaBottom)))

    /** Specular border: bright catch-light at the top, fading out at the bottom. */
    fun specular(strength: Float = 1f) = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = 0.30f * strength),
            Color.White.copy(alpha = 0.07f * strength),
            Color.White.copy(alpha = 0.16f * strength),
        ),
    )

    /** Haze style for floating bars/sheets: blur + dark tint so text stays readable. */
    val BlurStyle = HazeStyle(
        backgroundColor = Cosmos.Bg,
        tints = listOf(HazeTint(Color(0xB30A0618))),
        blurRadius = 24.dp,
        noiseFactor = 0.04f,
    )

    /** Lighter blur for in-content glass (cards over the nebula). */
    val SoftBlurStyle = HazeStyle(
        backgroundColor = Cosmos.Bg,
        tints = listOf(HazeTint(Color(0x800A0618))),
        blurRadius = 16.dp,
        noiseFactor = 0.03f,
    )
}

/**
 * The core material modifier. Use [floating]=true for surfaces that hover above
 * scrolling content (tab bar, headers, sheets) — they get real backdrop blur.
 */
fun Modifier.liquidGlass(
    shape: Shape = Liquid.ShapeMedium,
    tint: Color = Color.White,
    fillTopAlpha: Float = 0.10f,
    fillBottomAlpha: Float = 0.03f,
    specularStrength: Float = 1f,
    floating: Boolean = false,
): Modifier = composed {
    val haze = LocalHazeState.current
    val base = this.clip(shape)
    val blurred = if (floating && haze != null) {
        base.hazeEffect(state = haze, style = Liquid.BlurStyle)
    } else base
    blurred
        .background(Liquid.fill(fillTopAlpha, fillBottomAlpha, tint), shape)
        .border(1.dp, Liquid.specular(specularStrength), shape)
}

/** Spring press-scale + haptic tick. The standard Liquid interaction. */
fun Modifier.pressable(
    interaction: MutableInteractionSource,
    pressedScale: Float = 0.965f,
): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.6f),
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun rememberHaptic(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) } }
}

/** A floating glass surface (panel/card). */
@Composable
fun LiquidPanel(
    modifier: Modifier = Modifier,
    shape: Shape = Liquid.ShapeMedium,
    tint: Color = Color.White,
    floating: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .liquidGlass(shape = shape, tint = tint, floating = floating)
            .padding(contentPadding),
    ) {
        CompositionLocalProvider(LocalContentColor provides Cosmos.TextDim) { content() }
    }
}

/** Tappable glass card with press-scale + haptics. */
@Composable
fun LiquidCard(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: Shape = Liquid.ShapeMedium,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    val fillTop = if (selected) 0.16f else 0.09f
    val tint = if (selected) Cosmos.Violet else Color.White
    Box(
        modifier
            .then(if (onClick != null) Modifier.pressable(interaction) else Modifier)
            .clip(shape)
            .background(Liquid.fill(fillTop, 0.03f, tint), shape)
            .border(
                1.dp,
                if (selected) Brush.verticalGradient(
                    listOf(Cosmos.Violet.copy(alpha = 0.65f), Cosmos.Violet.copy(alpha = 0.25f)),
                ) else Liquid.specular(),
                shape,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null) {
                        haptic(); onClick()
                    }
                } else Modifier,
            )
            .padding(contentPadding),
    ) {
        CompositionLocalProvider(LocalContentColor provides Cosmos.TextDim) { content() }
    }
}

enum class LiquidButtonStyle { Primary, Glass, Destructive }

/** The standard button: gradient capsule with specular rim and press spring. */
@Composable
fun LiquidButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: LiquidButtonStyle = LiquidButtonStyle.Primary,
    enabled: Boolean = true,
    compact: Boolean = false,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    val shape = Liquid.ShapePill
    val bg: Brush = when {
        !enabled -> Liquid.fill(0.06f, 0.03f)
        style == LiquidButtonStyle.Primary -> Brush.verticalGradient(
            listOf(Cosmos.Violet, Cosmos.Violet2),
        )
        style == LiquidButtonStyle.Destructive -> Brush.verticalGradient(
            listOf(Color(0xFFFF6B76), Color(0xFFD8404C)),
        )
        else -> Liquid.fill(0.12f, 0.05f)
    }
    val fg = when {
        !enabled -> Cosmos.TextMuted
        style == LiquidButtonStyle.Glass -> Cosmos.Text
        else -> Color.White
    }
    Row(
        modifier
            .pressable(interaction)
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, Liquid.specular(if (style == LiquidButtonStyle.Glass) 1f else 1.4f), shape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) {
                haptic(); onClick()
            }
            .padding(
                horizontal = if (compact) 14.dp else 20.dp,
                vertical = if (compact) 8.dp else 12.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Circular glass icon button. */
@Composable
fun LiquidIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Cosmos.TextDim,
    size: Dp = 38.dp,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    Box(
        modifier
            .pressable(interaction)
            .size(size)
            .clip(Liquid.ShapePill)
            .background(
                if (active) Brush.verticalGradient(listOf(Cosmos.Violet, Cosmos.Violet2))
                else Liquid.fill(0.10f, 0.04f),
                Liquid.ShapePill,
            )
            .border(1.dp, Liquid.specular(), Liquid.ShapePill)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) {
                haptic(); onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) Color.White else if (enabled) tint else Cosmos.TextFaint,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/**
 * iOS-style segmented control on glass: equal-width segments, a sliding thumb
 * with spring physics.
 */
@Composable
fun LiquidSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
) {
    val haptic = rememberHaptic()
    Row(
        modifier
            .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.07f, fillBottomAlpha = 0.03f)
            .height(height)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            val alpha by animateFloatAsState(
                targetValue = if (selected) 1f else 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "seg$i",
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(Liquid.ShapePill)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Cosmos.Violet.copy(alpha = alpha),
                                Cosmos.Violet2.copy(alpha = alpha),
                            ),
                        ),
                        Liquid.ShapePill,
                    )
                    .then(
                        if (alpha > 0.5f) Modifier.border(1.dp, Liquid.specular(1.2f * alpha), Liquid.ShapePill)
                        else Modifier,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { haptic(); onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) Color.White else Cosmos.TextMuted,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Small selectable glass chip (filters, suggestions). */
@Composable
fun LiquidChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    Row(
        modifier
            .pressable(interaction)
            .clip(Liquid.ShapePill)
            .background(
                if (selected) Brush.verticalGradient(
                    listOf(Cosmos.Violet.copy(alpha = 0.32f), Cosmos.Violet2.copy(alpha = 0.22f)),
                ) else Liquid.fill(0.08f, 0.03f),
                Liquid.ShapePill,
            )
            .border(
                1.dp,
                if (selected) Brush.verticalGradient(
                    listOf(Cosmos.Violet.copy(alpha = 0.7f), Cosmos.Violet.copy(alpha = 0.3f)),
                ) else Liquid.specular(0.8f),
                Liquid.ShapePill,
            )
            .clickable(interactionSource = interaction, indication = null) { haptic(); onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) Cosmos.Violet3 else Cosmos.TextMuted,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Cosmos.Violet3 else Cosmos.TextDim,
            maxLines = 1,
        )
    }
}

/** iOS grouped-list container: stacked rows with inset separators. */
@Composable
fun LiquidGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Cosmos.TextMuted,
                modifier = Modifier.padding(start = 18.dp, bottom = 7.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlass(shape = Liquid.ShapeMedium, fillTopAlpha = 0.08f),
        ) {
            content()
        }
    }
}

/** A row inside a [LiquidGroup]. */
@Composable
fun LiquidRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                    } else Modifier,
                )
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = Cosmos.Text)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
                }
            }
            trailing?.invoke()
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(0.5.dp)
                    .background(Cosmos.HairlineSoft),
            )
        }
    }
}

/** Thin gradient progress bar. */
@Composable
fun LiquidProgress(progress: Float, modifier: Modifier = Modifier, height: Dp = 5.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(Liquid.ShapePill)
            .background(Cosmos.GlassFillStrong),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(Liquid.ShapePill)
                .background(Brush.horizontalGradient(listOf(Cosmos.Violet2, Cosmos.Violet))),
        )
    }
}
