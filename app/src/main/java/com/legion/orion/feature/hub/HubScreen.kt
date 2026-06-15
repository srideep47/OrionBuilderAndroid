package com.legion.orion.feature.hub

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.LiquidSegmented
import com.legion.orion.feature.media.MediaPane
import com.legion.orion.feature.media.PublishPane
import com.legion.orion.feature.network.NetworkScreen

/**
 * Hub — the network surface: P2P peers & friends, media generation queued to
 * peers, and social publishing.
 */
@Composable
fun HubScreen() {
    var pane by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Hub",
                    style = MaterialTheme.typography.titleLarge.copy(brush = Cosmos.TitleGradient),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Peers, shared compute, media, publishing",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                )
            }
        }

        LiquidSegmented(
            options = listOf("Peers", "Media", "Publish"),
            selectedIndex = pane,
            onSelect = { pane = it },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 6.dp),
        )

        when (pane) {
            0 -> NetworkScreen()
            1 -> MediaPane()
            else -> PublishPane()
        }
    }
}
