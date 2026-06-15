package com.legion.orion.feature.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.design.BadgeTone
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.CosmosBadge
import com.legion.orion.core.design.LiquidChip
import com.legion.orion.core.design.LiquidPanel
import com.legion.orion.core.design.LiquidSegmented
import com.legion.orion.core.design.LocalBottomChromeHeight
import com.legion.orion.core.design.MonoFamily
import com.legion.orion.feature.editor.EditorScreen
import com.legion.orion.feature.preview.PreviewScreen

/** Build workspace: editor, preview, safety report, and build-tool monitoring. */
@Composable
fun BuildWorkspace(vm: WorkspaceViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val projects by vm.projects.collectAsStateWithLifecycle()
    val buildJobs by vm.buildJobs.collectAsStateWithLifecycle()
    val server by vm.devServer.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()

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
                    "Build",
                    style = MaterialTheme.typography.titleLarge.copy(brush = Cosmos.TitleGradient),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Code, preview, safety, and build tools",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                )
            }
        }

        LiquidSegmented(
            options = listOf("Editor", "Preview", "Safety", "Tools"),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 6.dp),
        )

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
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp,
            bottom = LocalBottomChromeHeight.current + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Preview QA",
                            style = MaterialTheme.typography.titleMedium,
                            color = Cosmos.Text,
                            modifier = Modifier.weight(1f),
                        )
                        val ok = diagnostics.consoleErrors.isEmpty() && diagnostics.failedRequests.isEmpty()
                        CosmosBadge(if (ok) "Clean" else "Needs review", if (ok) BadgeTone.Green else BadgeTone.Amber)
                    }
                    Text(
                        "Browser console errors and failed requests are captured from the live preview and used by the agent QA gate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Cosmos.TextMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiquidChip("Refresh", onClick = onRefresh)
                        LiquidChip("Clear", onClick = onClear)
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
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
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
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
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
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp,
            bottom = LocalBottomChromeHeight.current + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Command tools", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                    ToolRow("Unified chat", "Use Chat → Build for create_project, edit files, start_dev_server, build_apk, and queue_media_job.")
                    ToolRow("Preview", serverUrl ?: "No dev server running.")
                    ToolRow("Projects", "$projectCount project(s) available to the editor and agent.")
                    ToolRow("QA status", "${diagnostics.consoleErrors.size} console error(s), ${diagnostics.failedRequests.size} failed request(s).")
                }
            }
        }
        item {
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Build jobs", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                    if (buildJobs.isEmpty()) {
                        Text("No build jobs yet.", style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
                    }
                }
            }
        }
        items(buildJobs, key = { it.id }) { job ->
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            job.target.uppercase(),
                            color = Cosmos.Violet3,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        CosmosBadge(
                            job.state,
                            when (job.state) {
                                "done" -> BadgeTone.Green
                                "failed" -> BadgeTone.Red
                                "running" -> BadgeTone.Violet
                                else -> BadgeTone.Amber
                            },
                        )
                    }
                    job.logTail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                            color = Cosmos.TextMuted,
                            maxLines = 8,
                        )
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
