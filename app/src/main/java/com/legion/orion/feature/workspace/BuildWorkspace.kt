package com.legion.orion.feature.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.legion.orion.feature.agent.AgentScreen
import com.legion.orion.feature.editor.EditorScreen
import com.legion.orion.feature.preview.PreviewScreen

/** The dev workspace: agentic builder, code editor, and live preview behind a segmented selector. */
@Composable
fun BuildWorkspace() {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Agent", "Editor", "Preview")

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            tabs.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = tab == i,
                    onClick = { tab = i },
                    shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                ) { Text(label) }
            }
        }
        when (tab) {
            0 -> AgentScreen()
            1 -> EditorScreen()
            else -> PreviewScreen()
        }
    }
}
