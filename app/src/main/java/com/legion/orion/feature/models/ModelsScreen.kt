package com.legion.orion.feature.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.database.DownloadEntity
import com.legion.orion.core.database.LocalModelEntity
import com.legion.orion.core.design.BadgeTone
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.CosmosBadge
import com.legion.orion.core.design.GhostButton
import com.legion.orion.core.design.GlassCard
import com.legion.orion.core.design.ScreenHeader
import com.legion.orion.core.design.glass
import com.legion.orion.data.marketplace.Capability
import com.legion.orion.data.marketplace.ModelSource
import com.legion.orion.data.marketplace.RemoteModel

@Composable
fun ModelsScreen(vm: ModelsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val locals by vm.localModels.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            eyebrow = "Local + Marketplace",
            title = "Models",
            subtitle = "Browse, download, and load on-device models",
        )

        Segmented(
            options = listOf("Marketplace", "Local (${locals.size})"),
            selectedIndex = if (ui.tab == ModelsTab.MARKETPLACE) 0 else 1,
            onSelect = { vm.setTab(if (it == 0) ModelsTab.MARKETPLACE else ModelsTab.LOCAL) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        when (ui.tab) {
            ModelsTab.MARKETPLACE -> Marketplace(ui, downloads, vm)
            ModelsTab.LOCAL -> LocalModels(locals, ui, vm)
        }
    }
}

@Composable
private fun Marketplace(ui: ModelsUiState, downloads: List<DownloadEntity>, vm: ModelsViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Search pill
            Row(
                Modifier.weight(1f).glass(RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = Cosmos.Violet, modifier = Modifier.padding(end = 6.dp))
                TextField(
                    value = ui.query,
                    onValueChange = vm::onQuery,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search models…", color = Cosmos.TextFaint) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { vm.search() }),
                    colors = transparentFieldColors(),
                )
            }
            GhostButton(text = "Search", onClick = vm::search)
        }

        Row(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModelSource.entries.forEach { src ->
                SourceChip(label = src.label, selected = ui.source == src) { vm.setSource(src) }
            }
        }

        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QUICK_TAGS.forEach { tag ->
                SourceChip(label = tag, selected = false) { vm.onQuery(tag); vm.search() }
            }
        }

        if (ui.searching) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cosmos.Violet)
            }
        }
        ui.error?.let {
            Text(it, color = Cosmos.TextMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall)
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(170.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(ui.results, key = { "${it.source}:${it.repoId}" }) { model ->
                val dl = downloads.firstOrNull { it.id == "${model.source.name}:${model.repoId}" }
                MarketplaceCard(model, dl) { vm.download(model) }
            }
        }
    }
}

@Composable
private fun MarketplaceCard(model: RemoteModel, dl: DownloadEntity?, onDownload: () -> Unit) {
    GlassCard(contentPadding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.org, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextFaint)
            Text(
                model.name,
                style = MaterialTheme.typography.titleMedium,
                color = Cosmos.Text,
                maxLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (model.mnnReady) CosmosBadge("MNN", BadgeTone.Green)
                model.quant?.let { CosmosBadge(it, BadgeTone.Amber) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                model.capabilities.take(2).forEach { CosmosBadge(capLabel(it), BadgeTone.Violet) }
            }
            Text(
                "↓ ${compact(model.downloads)}   ♥ ${compact(model.likes)}",
                style = MaterialTheme.typography.labelSmall,
                color = Cosmos.TextMuted,
            )
            when (dl?.state) {
                "running" -> {
                    val frac = if (dl.totalBytes > 0) dl.doneBytes.toFloat() / dl.totalBytes else 0f
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Cosmos.Violet,
                        trackColor = Cosmos.GlassFillStrong,
                    )
                    Text("${(frac * 100).toInt()}% · ${bytes(dl.doneBytes)} / ${bytes(dl.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
                }
                "done" -> CosmosBadge("Downloaded", BadgeTone.Green)
                "failed" -> {
                    CosmosBadge("Failed", BadgeTone.Red)
                    GhostButton(text = "Retry", onClick = onDownload, modifier = Modifier.fillMaxWidth())
                }
                else -> DownloadButton(onDownload)
            }
        }
    }
}

@Composable
private fun DownloadButton(onClick: () -> Unit) {
    GhostButton(
        text = "Download",
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        leading = { Icon(Icons.Filled.Download, null, tint = Cosmos.Violet) },
    )
}

@Composable
private fun LocalModels(
    locals: List<LocalModelEntity>,
    ui: ModelsUiState,
    vm: ModelsViewModel,
) {
    val loadingId = ui.loadingModelId
    var importPath by remember { mutableStateOf("") }
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${locals.size} model(s) on disk", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                            Text(ui.modelsPath, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted, maxLines = 2)
                        }
                        GhostButton(text = "Rescan", onClick = vm::rescan)
                    }
                    ui.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Cosmos.Green) }
                    Text(
                        "Have a model elsewhere? Paste the folder path (must contain config.json):",
                        style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = importPath,
                            onValueChange = { importPath = it },
                            modifier = Modifier.weight(1f).glass(RoundedCornerShape(8.dp)),
                            placeholder = { Text("/sdcard/…/MyModel", color = Cosmos.TextFaint) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            colors = transparentFieldColors(),
                        )
                        GhostButton(text = "Import", onClick = { vm.importPath(importPath); importPath = "" })
                    }
                }
            }
        }
        if (locals.isEmpty()) {
            item {
                Text(
                    "No models registered yet. Download one from the Marketplace, or Rescan / Import a folder above.",
                    style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextMuted,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        items(locals, key = { it.id }) { model ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(model.repoId?.substringAfterLast('/') ?: model.id, style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                        Text(
                            "${bytes(model.sizeBytes)} · ${model.source}",
                            style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            model.quant?.let { CosmosBadge(it, BadgeTone.Amber) }
                            model.capabilities.split(",").filter { it.isNotBlank() }.take(3)
                                .forEach { CosmosBadge(it, BadgeTone.Violet) }
                        }
                    }
                    if (loadingId == model.id) {
                        CircularProgressIndicator(color = Cosmos.Violet, modifier = Modifier.padding(8.dp))
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GhostButton(
                                text = "Load",
                                onClick = { vm.loadModel(model) },
                                leading = { Icon(Icons.Filled.PlayArrow, null, tint = Cosmos.Green) },
                            )
                            GhostButton(text = "Delete", onClick = { vm.deleteLocal(model) })
                        }
                    }
                }
            }
        }
    }
}

// ---- small UI atoms ----

@Composable
private fun Segmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.glass(RoundedCornerShape(10.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .glass(
                        RoundedCornerShape(8.dp),
                        fill = if (sel) Color(0x33A88CFF) else Color.Transparent,
                        border = Color.Transparent,
                    )
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) Cosmos.Text else Cosmos.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .glass(
                RoundedCornerShape(14.dp),
                fill = if (selected) Color(0x33A88CFF) else Cosmos.GlassFill,
                border = if (selected) Color(0x66A88CFF) else Cosmos.Hairline,
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) Cosmos.Violet3 else Cosmos.TextMuted)
    }
}

@Composable
private fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    cursorColor = Cosmos.Violet,
    focusedTextColor = Cosmos.Text,
    unfocusedTextColor = Cosmos.Text,
)

private val QUICK_TAGS = listOf("Qwen3", "Qwen2.5", "Llama", "Gemma", "DeepSeek-R1", "Phi", "MNN", "VL")

private fun capLabel(c: Capability) = c.label

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

private fun bytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1_000_000_000.0)
    b >= 1_000_000 -> "%.0f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "%.0f KB".format(b / 1_000.0)
    else -> "$b B"
}
