package com.legion.orion.feature.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.infra.p2p.FriendRequest
import com.legion.orion.infra.p2p.P2pPeer

@Composable
fun NetworkScreen(viewModel: NetworkViewModel = hiltViewModel()) {
    val online by viewModel.online.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val selfKey by viewModel.selfKey.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val trusted by viewModel.trusted.collectAsStateWithLifecycle()
    val pending by viewModel.pendingRequests.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.errors.collect { snackbarHostState.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                HeaderCard(online = online, busy = busy, selfKey = selfKey, trustedCount = trusted.size, mode = mode) {
                    viewModel.toggleOnline(it)
                }
            }

            if (pending.isNotEmpty()) {
                item { SectionLabel("Friend requests") }
                items(pending, key = { it.fromPublicKey }) { req ->
                    FriendRequestCard(req, onAccept = { viewModel.acceptFriend(req) }, onDismiss = { viewModel.dismissRequest(req) })
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
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
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Orion network", style = MaterialTheme.typography.titleMedium)
                        if (online && mode == "lan") {
                            Spacer(Modifier.width(8.dp))
                            Badge("LAN-only")
                        }
                    }
                    Text(
                        if (online) "Online · sharing & using compute" else "Offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Switch(checked = online, onCheckedChange = onToggle, enabled = !busy)
            }
            if (selfKey.isNotBlank()) {
                Spacer(Modifier.size(12.dp))
                HorizontalDivider()
                Spacer(Modifier.size(12.dp))
                Text("Your identity", style = MaterialTheme.typography.labelMedium)
                Text(
                    fingerprint(selfKey),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "$trustedCount trusted ${if (trustedCount == 1) "friend" else "friends"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FriendRequestCard(req: FriendRequest, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                req.fromDisplayName.ifBlank { "Unknown peer" },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "${req.fromDeviceName.ifBlank { "device" }} · ${fingerprint(req.fromPublicKey)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onAccept) { Text("Accept") }
            }
        }
    }
}

@Composable
private fun PeerCard(peer: P2pPeer, onAddFriend: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(online = peer.status == "online")
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(peer.displayName.ifBlank { "Unknown" }, style = MaterialTheme.typography.titleSmall)
                    Text(
                        fingerprint(peer.publicKey),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (peer.latencyMs != null) {
                    Text("${peer.latencyMs} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(peer.deviceType)
                if (peer.isLan) Badge("LAN")
                if (peer.isTrusted) Badge("Friend")
                if (peer.computeAvailable) Badge("Compute")
                Spacer(Modifier.weight(1f))
                if (!peer.isTrusted) {
                    OutlinedButton(onClick = onAddFriend) { Text("Add friend") }
                }
            }
            if (peer.loadedModels.isNotEmpty()) {
                Text(
                    "Models: ${peer.loadedModels.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    AssistChip(onClick = {}, enabled = false, label = { Text(text) })
}

@Composable
private fun StatusDot(online: Boolean) {
    val color = if (online) Color(0xFF34C759) else MaterialTheme.colorScheme.outline
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
}

private fun fingerprint(publicKey: String): String =
    if (publicKey.length >= 16) publicKey.substring(0, 16).uppercase() else publicKey.uppercase()
