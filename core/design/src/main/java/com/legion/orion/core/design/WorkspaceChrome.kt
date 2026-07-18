package com.legion.orion.core.design

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared chrome for Orion's top-level workspaces. It keeps title, purpose, status and navigation
 * aligned across Code, Models, Hub and Settings without giving every screen a bespoke tall header.
 */
@Composable
fun WorkspaceHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    status: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(brush = Cosmos.TitleGradient),
                fontWeight = FontWeight.Bold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Cosmos.TextMuted,
                maxLines = 1,
            )
        }
        status?.invoke()
    }
}

/** Compact, scroll-safe section navigation used when a workspace has more than three modes. */
@Composable
fun WorkspaceTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector?> = emptyList(),
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, label ->
            LiquidChip(
                text = label,
                selected = selectedIndex == index,
                icon = icons.getOrNull(index),
                onClick = { onSelect(index) },
            )
        }
        Spacer(Modifier.size(1.dp))
    }
}

@Composable
fun SectionIntro(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Cosmos.Text, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
    }
}
