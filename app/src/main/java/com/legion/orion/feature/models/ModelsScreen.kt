package com.legion.orion.feature.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.database.DownloadEntity
import com.legion.orion.core.database.LocalModelEntity
import com.legion.orion.core.design.BadgeTone
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.CosmosBadge
import com.legion.orion.core.design.Liquid
import com.legion.orion.core.design.LiquidButton
import com.legion.orion.core.design.LiquidButtonStyle
import com.legion.orion.core.design.LiquidChip
import com.legion.orion.core.design.LiquidPanel
import com.legion.orion.core.design.LiquidProgress
import com.legion.orion.core.design.LiquidSegmented
import com.legion.orion.core.design.LocalBottomChromeHeight
import com.legion.orion.core.design.MonoFamily
import com.legion.orion.core.design.WorkspaceHeader
import com.legion.orion.core.design.WorkspaceTabs
import com.legion.orion.core.design.liquidGlass
import com.legion.orion.data.marketplace.ModelSource
import com.legion.orion.data.marketplace.RemoteModel
import com.legion.orion.feature.engine.EngineScreen

/**
 * Models — everything on-device inference: the engine cockpit, the marketplace,
 * the local library, and the download manager — one screen, four panes.
 */
@Composable
fun ModelsScreen(vm: ModelsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val localModels by vm.localModels.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    var pane by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        WorkspaceHeader(
            title = "Models",
            subtitle = "Run, manage, and discover on-device AI",
            status = {
            val active = downloads.count { it.state == "running" || it.state == "queued" }
            if (active > 0) {
                CosmosBadge("$active downloading", BadgeTone.Violet)
            }
            },
        )

        WorkspaceTabs(
            options = listOf("Engine", "Library", "Discover", "Downloads"),
            selectedIndex = pane,
            onSelect = { pane = it },
        )

        when (pane) {
            0 -> EngineScreen()
            1 -> LocalPane(ui, localModels, vm)
            2 -> MarketPane(ui, downloads, vm)
            else -> DownloadsPane(downloads, vm)
        }
    }
}

// ─────────────────────────────── Marketplace ───────────────────────────────

@Composable
private fun MarketPane(ui: ModelsUiState, downloads: List<DownloadEntity>, vm: ModelsViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.08f)
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Search, null, tint = Cosmos.TextMuted, modifier = Modifier.size(16.dp))
            BasicTextField(
                value = ui.query,
                onValueChange = vm::onQuery,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Cosmos.Text),
                cursorBrush = SolidColor(Cosmos.Violet),
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (ui.query.isEmpty()) {
                            Text("Search models…", style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextFaint)
                        }
                        inner()
                    }
                },
            )
            Text(
                "Search",
                style = MaterialTheme.typography.labelMedium,
                color = Cosmos.Violet3,
                modifier = Modifier
                    .clickable { vm.search() }
                    .padding(4.dp),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModelSource.entries.forEach { s ->
                LiquidChip(s.label, selected = ui.source == s, onClick = { vm.setSource(s) })
            }
            Spacer(Modifier.size(6.dp))
            listOf("Qwen3", "Qwen2.5", "Llama", "Gemma", "DeepSeek-R1", "Phi").forEach { tag ->
                LiquidChip(tag, onClick = { vm.onQuery(tag); vm.search() })
            }
        }

        if (ui.searching) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cosmos.Violet)
            }
        }
        ui.error?.let {
            Text(
                it,
                color = Cosmos.Amber,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = LocalBottomChromeHeight.current + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(ui.results, key = { it.source.name + it.repoId }) { model ->
                val dl = downloads.firstOrNull {
                    it.modelId.endsWith(model.repoId.replace('/', '_')) || it.modelId.contains(model.repoId)
                }
                MarketCard(model, dl, onDownload = { vm.download(model) })
            }
        }
    }
}

@Composable
private fun MarketCard(model: RemoteModel, download: DownloadEntity?, onDownload: () -> Unit) {
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.org, style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily), color = Cosmos.TextMuted)
            Text(model.name, style = MaterialTheme.typography.titleSmall, color = Cosmos.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (model.mnnReady) CosmosBadge("MNN", BadgeTone.Green)
                model.quant?.let { CosmosBadge(it, BadgeTone.Blue) }
                model.capabilities.forEach { CosmosBadge(it.label, BadgeTone.Violet) }
                Spacer(Modifier.weight(1f))
                Text(
                    "↓ ${compact(model.downloads)} · ♥ ${compact(model.likes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                )
            }
            when {
                download != null && (download.state == "running" || download.state == "queued") -> {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        LiquidProgress(
                            progress = if (download.totalBytes > 0) {
                                download.doneBytes.toFloat() / download.totalBytes
                            } else 0f,
                        )
                        Text(
                            "${formatBytes(download.doneBytes)} / ${formatBytes(download.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                            color = Cosmos.TextMuted,
                        )
                    }
                }
                download?.state == "done" -> CosmosBadge("Downloaded", BadgeTone.Green)
                else -> LiquidButton(
                    "Download",
                    onClick = onDownload,
                    style = LiquidButtonStyle.Glass,
                    compact = true,
                    icon = Icons.Outlined.Download,
                )
            }
        }
    }
}

// ─────────────────────────────── Local library ───────────────────────────────

@Composable
private fun LocalPane(ui: ModelsUiState, models: List<LocalModelEntity>, vm: ModelsViewModel) {
    var importPath by rememberSaveable { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp,
            bottom = LocalBottomChromeHeight.current + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Memory, null, tint = Cosmos.Violet3, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "Storage",
                            style = MaterialTheme.typography.titleSmall,
                            color = Cosmos.Text,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatBytes(models.sumOf { it.sizeBytes }),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
                            color = Cosmos.Violet3,
                        )
                    }
                    Text(
                        ui.modelsPath,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                        color = Cosmos.TextMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiquidChip("Rescan disk", icon = Icons.Outlined.Refresh, onClick = vm::rescan)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.06f)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, null, tint = Cosmos.TextMuted, modifier = Modifier.size(14.dp))
                        BasicTextField(
                            value = importPath,
                            onValueChange = { importPath = it },
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.labelMedium.copy(color = Cosmos.Text, fontFamily = MonoFamily),
                            cursorBrush = SolidColor(Cosmos.Violet),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box {
                                    if (importPath.isEmpty()) {
                                        Text(
                                            "Import model folder path…",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Cosmos.TextFaint,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        Text(
                            "Import",
                            style = MaterialTheme.typography.labelSmall,
                            color = Cosmos.Violet3,
                            modifier = Modifier.clickable { vm.importPath(importPath); importPath = "" },
                        )
                    }
                    ui.notice?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = Cosmos.Green)
                    }
                }
            }
        }
        if (models.isEmpty()) {
            item {
                Text(
                    "No local models yet — download one from Market.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Cosmos.TextMuted,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        items(models, key = { it.id }) { model ->
            LocalCard(
                model = model,
                loading = ui.loadingModelId == model.id,
                onLoad = { vm.loadModel(model) },
                onDelete = { vm.deleteLocal(model) },
            )
        }
    }
}

@Composable
private fun LocalCard(
    model: LocalModelEntity,
    loading: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.repoId?.substringAfterLast('/') ?: model.id,
                        style = MaterialTheme.typography.titleSmall,
                        color = Cosmos.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${model.source} · ${formatBytes(model.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Cosmos.TextMuted,
                    )
                }
                Icon(
                    Icons.Outlined.Delete,
                    "Delete",
                    tint = Cosmos.TextFaint,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onDelete),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                model.quant?.let { CosmosBadge(it, BadgeTone.Blue) }
                model.capabilities.split(',').filter { it.isNotBlank() }.forEach {
                    CosmosBadge(it.trim(), BadgeTone.Violet)
                }
                model.lastUsedBackend?.let { CosmosBadge(it.uppercase(), BadgeTone.Neutral) }
                Spacer(Modifier.weight(1f))
                if (loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Cosmos.Violet, strokeWidth = 2.dp)
                } else {
                    LiquidButton("Load", onClick = onLoad, compact = true, icon = Icons.Outlined.PlayArrow)
                }
            }
        }
    }
}

// ─────────────────────────────── Downloads ───────────────────────────────

@Composable
private fun DownloadsPane(downloads: List<DownloadEntity>, vm: ModelsViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp,
            bottom = LocalBottomChromeHeight.current + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (downloads.isEmpty()) {
            item {
                Text(
                    "No downloads yet. Anything you grab in Market shows up here with progress, pause and resume.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Cosmos.TextMuted,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        items(downloads, key = { it.id }) { d ->
            LiquidPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            d.modelId.substringAfterLast('/'),
                            style = MaterialTheme.typography.titleSmall,
                            color = Cosmos.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        CosmosBadge(
                            d.state,
                            when (d.state) {
                                "done" -> BadgeTone.Green
                                "failed" -> BadgeTone.Red
                                "running" -> BadgeTone.Violet
                                else -> BadgeTone.Amber
                            },
                        )
                    }
                    if (d.state == "running" || d.state == "queued" || d.state == "paused") {
                        LiquidProgress(
                            progress = if (d.totalBytes > 0) d.doneBytes.toFloat() / d.totalBytes else 0f,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${formatBytes(d.doneBytes)} / ${formatBytes(d.totalBytes)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                                color = Cosmos.TextMuted,
                                modifier = Modifier.weight(1f),
                            )
                            if (d.state == "running") {
                                LiquidChip("Pause", onClick = { vm.pause(d.id) })
                            }
                        }
                    }
                    d.error?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = Cosmos.Red, maxLines = 2)
                    }
                }
            }
        }
    }
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_073_741_824.0
    val mb = bytes / 1_048_576.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.0f MB".format(mb)
        else -> "$bytes B"
    }
}
