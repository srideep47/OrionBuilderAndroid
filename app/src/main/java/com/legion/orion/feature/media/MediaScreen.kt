package com.legion.orion.feature.media

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.legion.orion.core.database.MediaJobEntity
import com.legion.orion.core.design.BadgeTone
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.CosmosBadge
import com.legion.orion.core.design.GhostButton
import com.legion.orion.core.design.GlassCard
import com.legion.orion.core.design.GlowButton
import com.legion.orion.core.design.ScreenHeader
import com.legion.orion.core.design.glass
import java.io.File

@Composable
fun MediaScreen(vm: MediaViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val publishJobs by vm.publishJobs.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshPeerState() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            eyebrow = "Generate on peers",
            title = "Media AI",
            subtitle = "Image & video jobs run on network peers — never on this device",
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Composer(ui, vm) }
            if (jobs.isNotEmpty()) {
                item {
                    Text("Queue & gallery", style = MaterialTheme.typography.titleMedium,
                        color = Cosmos.Text, modifier = Modifier.padding(top = 4.dp))
                }
            }
            items(jobs, key = { it.id }) { job -> JobCard(job, vm) }

            item { YouTubeCard(ui, vm) }
            if (publishJobs.isNotEmpty()) {
                item {
                    Text("Publishes", style = MaterialTheme.typography.titleMedium,
                        color = Cosmos.Text, modifier = Modifier.padding(top = 4.dp))
                }
                items(publishJobs, key = { it.id }) { pj -> PublishCard(pj) }
            }
        }
    }
}

@Composable
private fun YouTubeCard(ui: MediaUiState, vm: MediaViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("YouTube publishing", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text,
                    modifier = Modifier.weight(1f))
                when {
                    ui.ytConnected -> CosmosBadge("Connected", BadgeTone.Green)
                    ui.ytConfigured -> CosmosBadge("Configured", BadgeTone.Amber)
                    else -> CosmosBadge("Not set up", BadgeTone.Neutral)
                }
            }
            if (!ui.ytConnected) {
                Text(
                    "Add your Google OAuth client (YouTube Data API) to publish. Resumable 8 MB-chunk uploads survive interruption.",
                    style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted,
                )
                MediaField(clientId, { clientId = it }, "OAuth client ID")
                MediaField(clientSecret, { clientSecret = it }, "OAuth client secret")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(text = "Save", onClick = { vm.saveYouTubeClient(clientId, clientSecret) })
                    GhostButton(
                        text = "Sign in",
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
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(File(pj.mediaPath).name, style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextDim, maxLines = 1)
                Text(pj.platform, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
            }
            stateBadge(pj.state)
        }
    }
}

@Composable
private fun MediaField(value: String, onChange: (String) -> Unit, hint: String) {
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(8.dp)),
        placeholder = { Text(hint, color = Cosmos.TextFaint) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Cosmos.Violet,
            focusedTextColor = Cosmos.Text,
            unfocusedTextColor = Cosmos.Text,
        ),
    )
}

@Composable
private fun Composer(ui: MediaUiState, vm: MediaViewModel) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaType.entries.forEach { t ->
                    val sel = ui.type == t
                    Box(
                        Modifier
                            .glass(
                                RoundedCornerShape(10.dp),
                                fill = if (sel) Color(0x33A88CFF) else Cosmos.GlassFill,
                                border = if (sel) Color(0x66A88CFF) else Cosmos.Hairline,
                            )
                            .clickable { vm.setType(t) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(t.label, style = MaterialTheme.typography.labelLarge,
                            color = if (sel) Cosmos.Violet3 else Cosmos.TextMuted)
                    }
                }
            }
            TextField(
                value = ui.prompt,
                onValueChange = vm::onPrompt,
                modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(10.dp)),
                placeholder = { Text("Describe the ${ui.type.label.lowercase()} to generate…", color = Cosmos.TextFaint) },
                minLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Cosmos.Violet,
                    focusedTextColor = Cosmos.Text,
                    unfocusedTextColor = Cosmos.Text,
                ),
            )
            if (!ui.hasComputePeer) {
                Text(
                    "⚠ No compute peer online. The job will queue until a friend with spare compute connects.",
                    style = MaterialTheme.typography.bodySmall, color = Cosmos.Amber,
                )
            }
            GlowButton(
                text = "Queue to peers",
                onClick = vm::queue,
                enabled = ui.prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                leading = { Icon(Icons.Filled.AutoAwesome, null, tint = Color.White) },
            )
        }
    }
}

@Composable
private fun JobCard(job: MediaJobEntity, vm: MediaViewModel) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CosmosBadge(job.type.uppercase(), BadgeTone.Blue)
                Box(Modifier.width(8.dp))
                stateBadge(job.state)
                Box(Modifier.weight(1f))
                GhostButton(
                    text = if (job.state == "done") "Delete" else "Cancel",
                    onClick = { if (job.state == "done") vm.delete(job.id) else vm.cancel(job.id) },
                )
            }
            Text(job.prompt, style = MaterialTheme.typography.bodyMedium, color = Cosmos.TextDim, maxLines = 3)

            if (job.state == "running" || job.state == "assigned") {
                LinearProgressIndicator(
                    progress = { job.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Cosmos.Violet,
                    trackColor = Cosmos.GlassFillStrong,
                )
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
                            .clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Text("Saved: ${File(path).name}", style = MaterialTheme.typography.labelSmall, color = Cosmos.Green)
                }
                if (job.type == "video") {
                    GhostButton(
                        text = "Publish to YouTube",
                        onClick = { vm.publishVideo(path, job.prompt.take(90).ifBlank { "Orion video" }) },
                        modifier = Modifier.fillMaxWidth(),
                    )
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
    "queued" -> CosmosBadge("Queued", BadgeTone.Amber)
    "failed" -> CosmosBadge("Failed", BadgeTone.Red)
    else -> CosmosBadge(state, BadgeTone.Neutral)
}
