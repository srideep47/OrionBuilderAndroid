package com.legion.orion.feature.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.design.BadgeTone
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.CosmosBadge
import com.legion.orion.core.design.GhostButton
import com.legion.orion.core.design.GlassCard
import com.legion.orion.core.design.MonoFamily
import com.legion.orion.core.design.ScreenHeader
import com.legion.orion.core.design.glass
import com.legion.orion.feature.editor.EditorScreen
import com.legion.orion.feature.preview.PreviewScreen

/** Companion workspace for the unified Chat command surface: code, preview, security, and tools. */
@Composable
fun BuildWorkspace(vm: WorkspaceViewModel = hiltViewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Editor", "Preview", "Security", "Tools")
    val projects by vm.projects.collectAsStateWithLifecycle()
    val buildJobs by vm.buildJobs.collectAsStateWithLifecycle()
    val server by vm.devServer.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            eyebrow = "Workspace companion",
            title = "Build",
            subtitle = "Inspect code, preview output, review safety, and monitor build tools",
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .glass(
                            RoundedCornerShape(12.dp),
                            fill = if (tab == i) Color(0x33A88CFF) else Cosmos.GlassFill,
                            border = if (tab == i) Color(0x66A88CFF) else Cosmos.Hairline,
                        )
                        .clickable { tab = i }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(label, color = if (tab == i) Cosmos.Violet3 else Cosmos.TextMuted, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        when (tab) {
            0 -> EditorScreen()
            1 -> PreviewScreen()
            2 -> SecurityPanel(diagnostics, vm::refreshDiagnostics, vm::clearDiagnostics)
            else -> ToolsPanel(
                projectCount = projects.size,
                serverUrl = server?.url,
                buildJobs = buildJobs,
                diagnostics = diagnostics,
            )
        }
    }
}

@Composable
private fun SecurityPanel(
    diagnostics: WorkspaceDiagnostics,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Preview QA", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text, modifier = Modifier.weight(1f))
                        val ok = diagnostics.consoleErrors.isEmpty() && diagnostics.failedRequests.isEmpty()
                        CosmosBadge(if (ok) "Clean" else "Needs review", if (ok) BadgeTone.Green else BadgeTone.Amber)
                    }
                    Text(
                        "Browser console errors and failed requests are captured from the live preview and used by the agent QA gate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Cosmos.TextMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Refresh", onClick = onRefresh)
                        GhostButton("Clear", onClick = onClear)
                    }
                }
            }
        }
        item { SecurityChecklist() }
        item { DiagnosticList("Console errors", diagnostics.consoleErrors) }
        item { DiagnosticList("Failed requests", diagnostics.failedRequests) }
    }
}

@Composable
private fun SecurityChecklist() {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Agent safety rails", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
            listOf(
                "Project path jail for file tools",
                "Per-project write lock shared by editor and agent",
                "Shadow git snapshot before mutating tool batches",
                "Placeholder-code guard rejects incomplete writes",
                "Media generation always queues to trusted peers",
            ).forEach {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CosmosBadge("On", BadgeTone.Green)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Cosmos.TextDim)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticList(title: String, entries: List<String>) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
            if (entries.isEmpty()) {
                Text("No entries.", style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
            } else {
                entries.take(20).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily), color = Cosmos.Amber)
                }
            }
        }
    }
}

@Composable
private fun ToolsPanel(
    projectCount: Int,
    serverUrl: String?,
    buildJobs: List<com.legion.orion.core.database.BuildJobEntity>,
    diagnostics: WorkspaceDiagnostics,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Command tools", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                    ToolRow("Unified chat", "Use Chat -> Build for create_project, edit files, start_dev_server, build_apk, and queue_media_job.")
                    ToolRow("Preview", serverUrl ?: "No dev server running.")
                    ToolRow("Projects", "$projectCount project(s) available to the editor and agent.")
                    ToolRow("QA status", "${diagnostics.consoleErrors.size} console error(s), ${diagnostics.failedRequests.size} failed request(s).")
                }
            }
        }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Build jobs", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                    if (buildJobs.isEmpty()) {
                        Text("No build jobs yet.", style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
                    }
                }
            }
        }
        items(buildJobs, key = { it.id }) { job ->
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(job.target.uppercase(), color = Cosmos.Violet3, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        CosmosBadge(job.state, when (job.state) {
                            "done" -> BadgeTone.Green
                            "failed" -> BadgeTone.Red
                            "running" -> BadgeTone.Violet
                            else -> BadgeTone.Amber
                        })
                    }
                    job.logTail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily), color = Cosmos.TextMuted, maxLines = 8)
                    }
                    job.artifactPath?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = Cosmos.Green)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Cosmos.TextMuted)
        Text(value, style = MaterialTheme.typography.bodySmall, color = Cosmos.TextDim)
    }
}
