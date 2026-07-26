package com.legion.orion.feature.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.database.ProjectEntity
import com.legion.orion.core.design.BadgeTone
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.CosmosBadge
import com.legion.orion.core.design.LiquidButton
import com.legion.orion.core.design.LiquidButtonStyle
import com.legion.orion.core.design.LiquidPanel
import com.legion.orion.core.design.LocalBottomChromeHeight
import com.legion.orion.core.design.MonoFamily
import com.legion.orion.infra.p2p.FriendRequest
import com.legion.orion.infra.p2p.P2pPeer
import com.legion.orion.p2p.AssetOffer

/** Peers pane — P2P discovery, identity, and the friends/trust flow. */
@Composable
fun NetworkScreen(viewModel: NetworkViewModel = hiltViewModel()) {
    val online by viewModel.online.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val selfKey by viewModel.selfKey.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val trusted by viewModel.trusted.collectAsStateWithLifecycle()
    val pending by viewModel.pendingRequests.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val assetOffers by viewModel.assetOffers.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val activeProjectId by viewModel.activeProjectId.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.errors.collect { snackbarHostState.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = LocalBottomChromeHeight.current + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                HeaderCard(
                    online = online,
                    busy = busy,
                    selfKey = selfKey,
                    trustedCount = trusted.size,
                    mode = mode,
                    onToggle = viewModel::toggleOnline,
                )
            }

            if (pending.isNotEmpty()) {
                item { SectionLabel("Friend requests") }
                items(pending, key = { it.fromPublicKey }) { req ->
                    FriendRequestCard(
                        req,
                        onAccept = { viewModel.acceptFriend(req) },
                        onDismiss = { viewModel.dismissRequest(req) },
                    )
                }
            }

            if (assetOffers.isNotEmpty()) {
                item { SectionLabel("Incoming files") }
                items(assetOffers, key = { it.requestId }) { offer ->
                    AssetOfferCard(
                        offer = offer,
                        projects = projects,
                        defaultProjectId = activeProjectId,
                        onAccept = { projectId -> viewModel.acceptAsset(offer, projectId) },
                        onReject = { viewModel.rejectAsset(offer) },
                    )
                }
            }

            item { SectionLabel(if (peers.isEmpty()) "Peers" else "Peers (${peers.size})") }

            if (peers.isEmpty()) {
                item {
                    Text(
                        when {
                            !online -> "Go online to discover desktop and phone peers."
                            mode == "lan" -> "Searching for peers on your Wi-Fi network…"
                            else -> "Searching for peers on your network and the DHT…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cosmos.TextMuted,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(peers.sortedByDescending { it.status == "online" }, key = { it.publicKey }) { peer ->
                    PeerCard(peer, onAddFriend = { viewModel.requestFriend(peer.publicKey) })
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Cosmos.TextMuted,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

@Composable
private fun HeaderCard(
    online: Boolean,
    busy: Boolean,
    selfKey: String,
    trustedCount: Int,
    mode: String,
    onToggle: (Boolean) -> Unit,
) {
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Orion network", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
                        if (online && mode == "lan") CosmosBadge("LAN", BadgeTone.Blue)
                        if (online && mode != "lan") CosmosBadge("DHT", BadgeTone.Green)
                    }
                    Text(
                        if (online) "Online · sharing & using compute" else "Offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = Cosmos.TextMuted,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Cosmos.Violet)
                    Spacer(Modifier.width(12.dp))
                }
                Switch(
                    checked = online,
                    onCheckedChange = onToggle,
                    enabled = !busy,
                    colors = SwitchDefaults.colors(checkedTrackColor = Cosmos.Violet2, checkedThumbColor = Color.White),
                )
            }
            if (selfKey.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Your identity", style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
                    Text(
                        fingerprint(selfKey),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                        color = Cosmos.Violet3,
                    )
                    Text(
                        "$trustedCount trusted ${if (trustedCount == 1) "friend" else "friends"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Cosmos.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendRequestCard(req: FriendRequest, onAccept: () -> Unit, onDismiss: () -> Unit) {
    LiquidPanel(modifier = Modifier.fillMaxWidth(), tint = Cosmos.Violet) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                req.fromDisplayName.ifBlank { "Unknown peer" },
                style = MaterialTheme.typography.titleSmall,
                color = Cosmos.Text,
            )
            Text(
                "${req.fromDeviceName.ifBlank { "device" }} · ${fingerprint(req.fromPublicKey)}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                color = Cosmos.TextMuted,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                LiquidButton("Dismiss", onClick = onDismiss, style = LiquidButtonStyle.Glass, compact = true)
                LiquidButton("Accept", onClick = onAccept, compact = true)
            }
        }
    }
}

@Composable
private fun AssetOfferCard(
    offer: AssetOffer,
    projects: List<ProjectEntity>,
    defaultProjectId: String?,
    onAccept: (String) -> Unit,
    onReject: () -> Unit,
) {
    var selected by remember(offer.requestId, projects) {
        mutableStateOf(projects.firstOrNull { it.id == defaultProjectId } ?: projects.firstOrNull())
    }
    LiquidPanel(modifier = Modifier.fillMaxWidth(), tint = Cosmos.Violet) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(offer.fileName, style = MaterialTheme.typography.titleSmall, color = Cosmos.Text)
            Text(
                "${formatBytes(offer.sizeBytes)} · from a paired peer",
                style = MaterialTheme.typography.bodySmall,
                color = Cosmos.TextMuted,
            )
            if (projects.isEmpty()) {
                Text(
                    "Create a project first to receive this file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Cosmos.Amber,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Send to:", style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        selected?.name ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = Cosmos.Violet3,
                        modifier = Modifier.weight(1f),
                    )
                    LiquidButton(
                        "Change",
                        onClick = {
                            val index = projects.indexOf(selected)
                            selected = projects[(index + 1).mod(projects.size)]
                        },
                        style = LiquidButtonStyle.Glass,
                        compact = true,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                LiquidButton("Reject", onClick = onReject, style = LiquidButtonStyle.Glass, compact = true)
                LiquidButton(
                    "Accept",
                    onClick = { selected?.let { onAccept(it.id) } },
                    compact = true,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val exp = (kotlin.math.ln(bytes.toDouble()) / kotlin.math.ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    return "%.1f %s".format(bytes / Math.pow(1024.0, exp.toDouble()), units[exp])
}

@Composable
private fun PeerCard(peer: P2pPeer, onAddFriend: () -> Unit) {
    LiquidPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (peer.status == "online") Cosmos.Green else Cosmos.TextFaint),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(peer.displayName.ifBlank { "Unknown" }, style = MaterialTheme.typography.titleSmall, color = Cosmos.Text)
                    Text(
                        fingerprint(peer.publicKey),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                        color = Cosmos.TextMuted,
                    )
                }
                peer.latencyMs?.let {
                    Text(
                        "$it ms",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                        color = if (it < 50) Cosmos.Green else if (it < 150) Cosmos.Amber else Cosmos.Red,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CosmosBadge(peer.deviceType, BadgeTone.Neutral)
                if (peer.isLan) CosmosBadge("LAN", BadgeTone.Blue)
                if (peer.isTrusted) CosmosBadge("Friend", BadgeTone.Violet)
                if (peer.computeAvailable) CosmosBadge("Compute", BadgeTone.Green)
                Spacer(Modifier.weight(1f))
                if (!peer.isTrusted) {
                    LiquidButton("Add friend", onClick = onAddFriend, style = LiquidButtonStyle.Glass, compact = true)
                }
            }
            if (peer.loadedModels.isNotEmpty()) {
                Text(
                    "Models: ${peer.loadedModels.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Cosmos.TextMuted,
                )
            }
        }
    }
}

private fun fingerprint(publicKey: String): String =
    if (publicKey.length >= 16) publicKey.substring(0, 16).uppercase() else publicKey.uppercase()
