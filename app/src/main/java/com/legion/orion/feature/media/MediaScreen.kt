package com.legion.orion.feature.media

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.legion.orion.core.database.MediaJobEntity
import com.legion.orion.core.design.BadgeTone
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.CosmosBadge
import com.legion.orion.core.design.Liquid
import com.legion.orion.core.design.LiquidButton
import com.legion.orion.core.design.LiquidButtonStyle
import com.legion.orion.core.design.LiquidChip
import com.legion.orion.core.design.LiquidPanel
import com.legion.orion.core.design.LiquidProgress
import com.legion.orion.core.design.LocalBottomChromeHeight
import com.legion.orion.core.design.liquidGlass
import com.legion.orion.data.media.RemoteCatalog
import com.legion.orion.data.media.ShareDownloadState
import com.legion.orion.infra.p2p.SharedMediaItem
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

/** Media pane — queue image/video/audio jobs to peers and browse results. */
@Composable
fun MediaPane(vm: MediaViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshPeerState() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp,
            bottom = LocalBottomChromeHeight.current + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Composer(ui, vm) }
        if (jobs.isNotEmpty()) {
            item {
                Text(
                    "Queue & gallery",
                    style = MaterialTheme.typography.titleMedium,
                    color = Cosmos.Text,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        items(jobs, key = { it.id }) { job -> JobCard(job, vm) }
    }
}

/** Publish pane — YouTube OAuth, publish queue, and scheduled publishes. */
@Composable
fun PublishPane(vm: MediaViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val publishJobs by vm.publishJobs.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshPeerState() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp,
            bottom = LocalBottomChromeHeight.current + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { YouTubeCard(ui, vm) }

        val publishableVideos = jobs.filter {
            it.state == "done" && it.type == "video" && it.resultPath != null && File(it.resultPath!!).exists()
        }
        if (publishableVideos.isNotEmpty()) {
            item {
                Text(
                    "Ready to publish",
                    style = MaterialTheme.typography.titleMedium,
                    color = Cosmos.Text,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(publishableVideos, key = { "pub-" + it.id }) { job ->
                PublishComposer(job, vm)
            }
        }

        if (publishJobs.isNotEmpty()) {
            item {
                Text(
                    "Publish queue",
                    style = MaterialTheme.typography.titleMedium,
                    color = Cosmos.Text,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(publishJobs, key = { it.id }) { pj -> PublishCard(pj) }
        }

        if (publishJobs.isEmpty() && publishableVideos.isEmpty()) {
            item {
                Text(
                    "Generate a video in Media, then publish or schedule it here. Uploads run in 8 MB chunks and survive interruption and reboots.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Cosmos.TextMuted,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun PublishComposer(job: MediaJobEntity, vm: MediaViewModel) {
    var schedule by rememberSaveable("sched-${job.id}") { mutableStateOf("Now") }
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                File(job.resultPath!!).name,
                style = MaterialTheme.typography.titleSmall,
                color = Cosmos.Text,
                maxLines = 1,
            )
            Text(job.prompt, style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Now", "In 1h", "In 8h", "In 24h").forEach { opt ->
                    LiquidChip(opt, selected = schedule == opt, onClick = { schedule = opt })
                }
            }
            LiquidButton(
                if (schedule == "Now") "Publish to YouTube" else "Schedule ($schedule)",
                onClick = {
                    val title = job.prompt.take(90).ifBlank { "Orion video" }
                    val path = job.resultPath!!
                    when (schedule) {
                        "In 1h" -> vm.publishVideoAt(path, title, 60L * 60 * 1000)
                        "In 8h" -> vm.publishVideoAt(path, title, 8L * 60 * 60 * 1000)
                        "In 24h" -> vm.publishVideoAt(path, title, 24L * 60 * 60 * 1000)
                        else -> vm.publishVideo(path, title)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun YouTubeCard(ui: MediaUiState, vm: MediaViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "YouTube publishing",
                    style = MaterialTheme.typography.titleMedium,
                    color = Cosmos.Text,
                    modifier = Modifier.weight(1f),
                )
                when {
                    ui.ytConnected -> CosmosBadge("Connected", BadgeTone.Green)
                    ui.ytConfigured -> CosmosBadge("Configured", BadgeTone.Amber)
                    else -> CosmosBadge("Not set up", BadgeTone.Neutral)
                }
            }
            if (!ui.ytConnected) {
                Text(
                    "Add your Google OAuth client (YouTube Data API) to publish. Resumable 8 MB-chunk uploads survive interruption.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Cosmos.TextMuted,
                )
                MediaField(clientId, { clientId = it }, "OAuth client ID")
                MediaField(clientSecret, { clientSecret = it }, "OAuth client secret")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidButton(
                        "Save",
                        onClick = { vm.saveYouTubeClient(clientId, clientSecret) },
                        style = LiquidButtonStyle.Glass,
                        compact = true,
                    )
                    LiquidButton(
                        "Sign in",
                        compact = true,
                        onClick = {
                            scope.launch {
                                vm.consentUrl()?.let {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PublishCard(pj: com.legion.orion.core.database.PublishJobEntity) {
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    File(pj.mediaPath).name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cosmos.TextDim,
                    maxLines = 1,
                )
                val sub = buildString {
                    append(pj.platform)
                    pj.scheduledAt?.takeIf { it > System.currentTimeMillis() }?.let {
                        append(" · fires in ${(it - System.currentTimeMillis()) / 60_000} min")
                    }
                }
                Text(sub, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
            }
            stateBadge(pj.state)
        }
    }
}

@Composable
private fun MediaField(value: String, onChange: (String) -> Unit, hint: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .liquidGlass(shape = Liquid.ShapePill, fillTopAlpha = 0.07f)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Cosmos.Text),
            cursorBrush = SolidColor(Cosmos.Violet),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(hint, style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextFaint)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun Composer(ui: MediaUiState, vm: MediaViewModel) {
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaType.entries.forEach { t ->
                    LiquidChip(t.label, selected = ui.type == t, onClick = { vm.setType(t) })
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .liquidGlass(shape = RoundedCornerShape(14.dp), fillTopAlpha = 0.06f)
                    .padding(12.dp),
            ) {
                BasicTextField(
                    value = ui.prompt,
                    onValueChange = vm::onPrompt,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Cosmos.Text),
                    cursorBrush = SolidColor(Cosmos.Violet),
                    minLines = 3,
                    decorationBox = { inner ->
                        Box {
                            if (ui.prompt.isEmpty()) {
                                Text(
                                    "Describe the ${ui.type.label.lowercase()} to generate…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Cosmos.TextFaint,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            if (!ui.hasComputePeer) {
                Text(
                    "⚠ No compute peer online. Generation runs on a trusted peer (e.g. your desktop) — it never runs on this phone. The job waits until one connects.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Cosmos.Amber,
                )
            }
            LiquidButton(
                "Generate on a peer",
                onClick = vm::queue,
                enabled = ui.prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Outlined.AutoAwesome,
            )
        }
    }
}

@Composable
private fun JobCard(job: MediaJobEntity, vm: MediaViewModel) {
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CosmosBadge(job.type.uppercase(), BadgeTone.Blue)
                Box(Modifier.width(8.dp))
                stateBadge(job.state)
                Box(Modifier.weight(1f))
                LiquidButton(
                    if (job.state == "done") "Delete" else "Cancel",
                    onClick = { if (job.state == "done") vm.delete(job.id) else vm.cancel(job.id) },
                    style = LiquidButtonStyle.Glass,
                    compact = true,
                )
            }
            Text(job.prompt, style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextDim, maxLines = 3)

            if (job.state == "running" || job.state == "assigned") {
                LiquidProgress(progress = job.progress / 100f, height = 4.dp)
            }

            val path = job.resultPath
            if (job.state == "done" && path != null && File(path).exists()) {
                if (job.type == "image") {
                    AsyncImage(
                        model = File(path),
                        contentDescription = job.prompt,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Text("Saved: ${File(path).name}", style = MaterialTheme.typography.labelSmall, color = Cosmos.Green)
                }
            }
        }
    }
}

@Composable
private fun stateBadge(state: String) = when (state) {
    "done" -> CosmosBadge("Done", BadgeTone.Green)
    "running" -> CosmosBadge("Running", BadgeTone.Violet)
    "assigned" -> CosmosBadge("Assigned", BadgeTone.Violet)
    "uploading" -> CosmosBadge("Uploading", BadgeTone.Violet)
    "scheduled" -> CosmosBadge("Scheduled", BadgeTone.Blue)
    "queued" -> CosmosBadge("Queued", BadgeTone.Amber)
    "failed" -> CosmosBadge("Failed", BadgeTone.Red)
    else -> CosmosBadge(state, BadgeTone.Neutral)
}

// ─────────────────────────── Shared media (network) ───────────────────────────

/** Shared pane — browse and download media trusted peers are sharing on the network. */
@Composable
fun SharedMediaPane(vm: MediaViewModel = hiltViewModel()) {
    val catalogs by vm.catalogs.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshShared() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp,
            bottom = LocalBottomChromeHeight.current + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Shared on the network", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                    Text(
                        "Media your trusted peers are sharing — your gallery is shared back automatically.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Cosmos.TextMuted,
                    )
                }
                LiquidButton(
                    "Refresh",
                    onClick = vm::refreshShared,
                    style = LiquidButtonStyle.Glass,
                    compact = true,
                    icon = Icons.Outlined.Refresh,
                )
            }
        }

        if (catalogs.isEmpty()) {
            item { EmptySharedMedia() }
        }

        catalogs.forEach { cat ->
            item(key = "hdr-${cat.peerKey}") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Folder, null, tint = Cosmos.Violet3, modifier = Modifier.size(16.dp))
                    Text(
                        cat.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Cosmos.Text,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CosmosBadge("${cat.items.size}", BadgeTone.Neutral)
                }
            }
            items(cat.items, key = { "${cat.peerKey}/${it.fileName}" }) { item ->
                SharedItemCard(cat.peerKey, item, downloads["${cat.peerKey}/${item.fileName}"], vm)
            }
        }
    }
}

@Composable
private fun EmptySharedMedia() {
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.CloudOff, null, tint = Cosmos.TextMuted, modifier = Modifier.size(20.dp))
            Text(
                "No shared media yet. When a trusted peer (e.g. your desktop) shares media, it appears here. Tap Refresh after connecting.",
                style = MaterialTheme.typography.bodySmall,
                color = Cosmos.TextMuted,
            )
        }
    }
}

@Composable
private fun SharedItemCard(
    peerKey: String,
    item: SharedMediaItem,
    dl: ShareDownloadState?,
    vm: MediaViewModel,
) {
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x1FA88CFF)),
                contentAlignment = Alignment.Center,
            ) {
                if (!item.thumbnail.isNullOrBlank()) {
                    AsyncImage(
                        model = item.thumbnail,
                        contentDescription = item.prompt,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(kindIcon(item.kind), null, tint = Cosmos.Violet3, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    item.prompt?.takeIf { it.isNotBlank() } ?: item.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cosmos.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${item.kind.uppercase()} · ${formatBytes(item.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                )
                if (dl?.status == "downloading") {
                    val frac = if (dl.total > 0) (dl.received.toFloat() / dl.total).coerceIn(0f, 1f) else 0f
                    Spacer(Modifier.height(4.dp))
                    LiquidProgress(progress = frac, height = 3.dp)
                }
            }
            when (dl?.status) {
                "done" -> CosmosBadge("Saved", BadgeTone.Green)
                "downloading" -> CircularProgressIndicator(Modifier.size(18.dp), color = Cosmos.Violet, strokeWidth = 2.dp)
                "error" -> LiquidButton("Retry", onClick = { vm.downloadShared(peerKey, item) }, style = LiquidButtonStyle.Glass, compact = true)
                else -> LiquidButton("Get", onClick = { vm.downloadShared(peerKey, item) }, compact = true, icon = Icons.Outlined.Download)
            }
        }
    }
}

private fun kindIcon(kind: String): ImageVector = when (kind.lowercase()) {
    "video" -> Icons.Outlined.Movie
    "audio", "music" -> Icons.Outlined.MusicNote
    else -> Icons.Outlined.Image
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", v, units[i])
}
