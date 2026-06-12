package com.legion.orion.core.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Translucent glass fill + hairline border. The cosmic background shows through,
 * which is what reads as "liquid glass" on top of the animated backdrop. */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(14.dp),
    fill: Color = Cosmos.GlassFill,
    border: Color = Cosmos.Hairline,
): Modifier = this
    .clip(shape)
    .background(fill, shape)
    .border(BorderStroke(1.dp, border), shape)

/** A standard glass panel/card. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    fill: Color = Cosmos.GlassFill,
    border: Color = Cosmos.Hairline,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Box(modifier.glass(shape, fill, border).padding(contentPadding)) { content() }
}

/** Section header in mono caps, like the desktop `.eyebrow`. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Cosmos.Violet.copy(alpha = 0.7f)) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

/** Primary gradient action button (violet → deep violet). */
@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    val brush = if (enabled) Cosmos.PrimaryGradient
    else Brush.linearGradient(listOf(Cosmos.GlassFillStrong, Cosmos.GlassFill))
    Row(
        modifier
            .clip(shape)
            .background(brush, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Color.White else Cosmos.TextMuted,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Outlined/ghost button. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .glass(shape, fill = Cosmos.GlassFill, border = Cosmos.Hairline)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Cosmos.TextDim else Cosmos.TextMuted,
        )
    }
}

enum class BadgeTone { Neutral, Violet, Green, Amber, Red, Blue }

@Composable
fun CosmosBadge(text: String, tone: BadgeTone = BadgeTone.Neutral, modifier: Modifier = Modifier) {
    val (fill, stroke, fg) = when (tone) {
        BadgeTone.Violet -> Triple(Color(0x24A88CFF), Color(0x47A88CFF), Cosmos.Violet3)
        BadgeTone.Green -> Triple(Color(0x243CC88C), Color(0x4D3CC88C), Cosmos.Green)
        BadgeTone.Amber -> Triple(Color(0x24FFB43C), Color(0x47FFB43C), Cosmos.Amber)
        BadgeTone.Red -> Triple(Color(0x24FF505A), Color(0x47FF505A), Cosmos.Red)
        BadgeTone.Blue -> Triple(Color(0x2478B4FF), Color(0x4778B4FF), Cosmos.Blue)
        BadgeTone.Neutral -> Triple(Cosmos.GlassFillStrong, Cosmos.Hairline, Cosmos.TextMuted)
    }
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, stroke, shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Glass card with a hover/press ripple, used for tappable list items. */
@Composable
fun GlassCard(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(12.dp),
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable () -> Unit,
) {
    val fill = if (selected) Color(0x14A88CFF) else Cosmos.GlassFill
    val border = if (selected) Color(0x80A88CFF) else Cosmos.Hairline
    val base = Modifier
        .glass(shape, fill, border)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(contentPadding)
    Box(modifier.then(base)) {
        CompositionLocalProvider(LocalContentColor provides Cosmos.TextDim) { content() }
    }
}
