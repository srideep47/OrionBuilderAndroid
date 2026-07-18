package com.legion.orion.feature.workspace

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.legion.orion.core.datastore.GitAccount
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.Liquid
import com.legion.orion.core.design.LiquidButton
import com.legion.orion.core.design.LiquidPanel
import com.legion.orion.core.design.MonoFamily
import com.legion.orion.core.design.liquidGlass

/**
 * Multiple named HTTPS git credential profiles — replaces the old single per-host PAT field. SSH
 * stays a single shared on-device keypair ([GitRemoteManager.ensureSshKey]), unaffected here; this
 * is HTTPS-PAT accounts only, per the locked scope decision.
 */
@Composable
fun GitAccountsPanel(
    accounts: List<GitAccount>,
    githubLogin: GitHubLoginState,
    onAdd: (label: String, host: String, username: String?, pat: String) -> Unit,
    onRemove: (String) -> Unit,
    onSignInWithGitHub: () -> Unit,
    onDismissGitHubLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var label by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("github.com") }
    var username by remember { mutableStateOf("") }
    var pat by remember { mutableStateOf("") }

    LiquidPanel(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Git accounts", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
            Text(
                "Named HTTPS access-token profiles. A token is only embedded into a remote URL for " +
                    "the duration of one push/pull/fetch/clone, then stripped back out — never left " +
                    "sitting in .git/config. With 2+ accounts you'll be asked which one to use.",
                style = MaterialTheme.typography.bodySmall,
                color = Cosmos.TextMuted,
            )
            if (githubLogin.busy || githubLogin.error != null) {
                GitHubLoginBanner(state = githubLogin, onDismiss = onDismissGitHubLogin)
            } else {
                LiquidButton(
                    "Sign in with GitHub",
                    compact = true,
                    onClick = onSignInWithGitHub,
                )
            }
            Text(
                "— or add a token manually —",
                style = MaterialTheme.typography.labelSmall,
                color = Cosmos.TextFaint,
            )
            accounts.forEach { account ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Person, null, tint = Cosmos.Violet3, modifier = Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(account.label, style = MaterialTheme.typography.bodyMedium, color = Cosmos.Text)
                        Text(
                            account.username?.let { "$it @ ${account.host}" } ?: account.host,
                            style = MaterialTheme.typography.labelSmall,
                            color = Cosmos.TextMuted,
                        )
                    }
                    Icon(
                        Icons.Outlined.Delete,
                        "Remove",
                        tint = Cosmos.Red,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onRemove(account.id) },
                    )
                }
            }
            if (accounts.isEmpty()) {
                Text("No accounts yet — add one below.", style = MaterialTheme.typography.bodySmall, color = Cosmos.TextMuted)
            }
            GitTextField(label, { label = it }, "label (e.g. work, personal)")
            GitTextField(host, { host = it }, "github.com")
            GitTextField(username, { username = it }, "username (optional)")
            GitTextField(pat, { pat = it }, "personal access token", obscure = true)
            LiquidButton(
                "Add account",
                compact = true,
                icon = Icons.Outlined.Add,
                onClick = {
                    if (label.isNotBlank() && host.isNotBlank() && pat.isNotBlank()) {
                        onAdd(label.trim(), host.trim(), username.trim().takeIf { it.isNotBlank() }, pat.trim())
                        label = ""; username = ""; pat = ""
                    }
                },
            )
        }
    }
}

/**
 * The GitHub OAuth device-flow sign-in status: shows a spinner until GitHub issues a user code,
 * then the code itself plus a button to open github.com/login/device — the user approves there in
 * their own browser, same as any other device-flow sign-in (a TV app, a CLI tool, etc.), and this
 * banner disappears on its own once [WorkspaceViewModel.beginGitHubLogin] detects the resulting
 * token and adds the account.
 */
@Composable
private fun GitHubLoginBanner(state: GitHubLoginState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.1f)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Sign in with GitHub",
                style = MaterialTheme.typography.labelLarge,
                color = Cosmos.Text,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Outlined.Close, "Cancel",
                tint = Cosmos.TextFaint,
                modifier = Modifier.size(16.dp).clickable(onClick = onDismiss),
            )
        }
        when {
            state.error != null -> Text(state.error, style = MaterialTheme.typography.labelSmall, color = Cosmos.Red)
            state.userCode != null -> {
                Text(
                    "Enter this code at ${state.verificationUri}:",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cosmos.TextMuted,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        state.userCode,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFamily),
                        color = Cosmos.Violet3,
                        modifier = Modifier
                            .clickable { clipboard.setText(AnnotatedString(state.userCode)) },
                    )
                    CircularProgressIndicator(Modifier.size(14.dp), color = Cosmos.Violet, strokeWidth = 1.5.dp)
                }
                LiquidButton(
                    "Open github.com/login/device",
                    compact = true,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.verificationUri)))
                        }
                    },
                )
            }
            else -> {
                CircularProgressIndicator(Modifier.size(14.dp), color = Cosmos.Violet, strokeWidth = 1.5.dp)
                Text("Requesting a code from GitHub…", style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
            }
        }
    }
}

/** Shown only when 2+ git accounts exist (see [WorkspaceViewModel.withAccountGate]) — with 0 or 1
 * accounts the gated op just runs immediately with no prompt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitAccountPickerDialog(accounts: List<GitAccount>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF20A0618),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Which account?",
                style = MaterialTheme.typography.titleMedium.copy(brush = Cosmos.TitleGradient),
                fontWeight = FontWeight.Bold,
            )
            accounts.forEach { account ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(account.id) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.Person, null, tint = Cosmos.Violet3)
                    Column {
                        Text(account.label, style = MaterialTheme.typography.bodyMedium, color = Cosmos.Text)
                        Text(account.host, style = MaterialTheme.typography.labelSmall, color = Cosmos.TextMuted)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Standalone clone dialog for the file manager's long-press menu (unlike the Git tab, which has its
 * own persistent URL field already on screen). Always clones into a new top-level project. */
@Composable
fun CloneRepoDialog(onClone: (url: String, name: String?) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB0000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .liquidGlass(shape = Liquid.ShapeSmall, fillTopAlpha = 0.12f)
                .clickable(enabled = false) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Clone repository", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
            Text(
                "Always creates a new top-level project, wherever this was opened from.",
                style = MaterialTheme.typography.labelSmall,
                color = Cosmos.TextMuted,
            )
            GitTextField(url, { url = it }, "https://github.com/owner/repo.git")
            GitTextField(name, { name = it }, "project name (optional)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidButton("Cancel", compact = true, onClick = onDismiss)
                LiquidButton(
                    "Clone",
                    compact = true,
                    onClick = {
                        if (url.isNotBlank()) {
                            onClone(url.trim(), name.trim().takeIf { it.isNotBlank() })
                            onDismiss()
                        }
                    },
                )
            }
        }
    }
}

/** The file manager's long-press-empty-space menu: clone (always) plus push/pull/fetch (only when
 * the current project is a git repo). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerGitMenuSheet(
    hasActiveProjectGit: Boolean,
    onClone: () -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
    onFetch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF20A0618),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            MenuRow("Clone repository…", Icons.Outlined.CloudDownload, onClone)
            if (hasActiveProjectGit) {
                MenuRow("Push", Icons.Outlined.Upload, onPush)
                MenuRow("Pull", Icons.Outlined.SyncAlt, onPull)
                MenuRow("Fetch", Icons.Outlined.Sync, onFetch)
            }
        }
    }
}
