package com.legion.orion.feature.preview

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.GhostButton
import com.legion.orion.core.design.GlassCard
import com.legion.orion.core.design.GlowButton
import com.legion.orion.core.design.glass

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(vm: PreviewViewModel = hiltViewModel()) {
    val server by vm.devServer.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val fullscreen by vm.fullscreen.collectAsStateWithLifecycle()
    val panel by vm.panel.collectAsStateWithLifecycle()
    val browser by vm.browserState.collectAsStateWithLifecycle()
    val runtimeLogs by vm.runtimeLogs.collectAsStateWithLifecycle()
    val a11y by vm.a11yTree.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    if (server == null) {
        EmptyPreview(busy, error, vm::startPreview)
        return
    }

    val controller = vm.previewController
    val rootUrl = server!!.url
    var address by remember(rootUrl) { mutableStateOf(rootUrl) }

    LaunchedEffect(browser.url) {
        if (browser.url.isNotBlank()) address = browser.url
    }

    DisposableEffect(rootUrl) {
        controller.clearDiagnostics()
        onDispose { controller.detach() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(if (fullscreen) Color.Black else Color.Transparent),
    ) {
        BrowserToolbar(
            address = address,
            browser = browser,
            busy = busy,
            fullscreen = fullscreen,
            onAddress = { address = it },
            onSubmit = { vm.openPath(address) },
            onBack = vm::goBack,
            onForward = vm::goForward,
            onReload = vm::reload,
            onHome = { vm.openPath("/") },
            onRestart = vm::restartPreview,
            onOpenExternal = { uriHandler.openUri(browser.url.ifBlank { rootUrl }) },
            onFullscreen = vm::toggleFullscreen,
            onStop = vm::stopPreview,
        )

        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                    controller.recordConsole(
                                        msg.messageLevel().name,
                                        msg.message(),
                                        msg.sourceId(),
                                        msg.lineNumber(),
                                    )
                                    return true
                                }

                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    controller.updateBrowserState(view)
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    controller.updateBrowserState(view)
                                }

                                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                                    controller.recordFailedRequest("${err.errorCode} ${req.url}")
                                }

                                override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, resp: WebResourceResponse) {
                                    if (resp.statusCode >= 400) controller.recordFailedRequest("${resp.statusCode} ${req.url}")
                                }
                            }
                            controller.attach(this)
                            loadUrl(rootUrl)
                        }
                    },
                    update = { controller.updateBrowserState(it) },
                )
            }

            if (!fullscreen) {
                ToolRail(panel = panel, onSelect = vm::setPanel)
            }
        }

        if (!fullscreen) {
            BottomPanel(
                panel = panel,
                diagnostics = controller.diagnostics(),
                console = controller.consoleLog(),
                runtimeLogs = runtimeLogs,
                a11y = a11y,
                onClear = vm::clearDiagnostics,
                onRefreshA11y = vm::refreshAccessibility,
            )
        }
    }
}

@Composable
private fun EmptyPreview(busy: Boolean, error: String?, onStart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No preview running", style = MaterialTheme.typography.titleMedium, color = Cosmos.Text)
        Text(
            "Start the active chat project here, or ask the agent to run start_dev_server.",
            style = MaterialTheme.typography.bodySmall,
            color = Cosmos.TextMuted,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        GlowButton("Run project preview", onClick = onStart, enabled = !busy)
        if (busy) CircularProgressIndicator(Modifier.padding(top = 14.dp), color = Cosmos.Violet)
        error?.let { Text(it, color = Cosmos.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
    }
}

@Composable
private fun BrowserToolbar(
    address: String,
    browser: PreviewController.BrowserState,
    busy: Boolean,
    fullscreen: Boolean,
    onAddress: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onHome: () -> Unit,
    onRestart: () -> Unit,
    onOpenExternal: () -> Unit,
    onFullscreen: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            .glass(RoundedCornerShape(14.dp), fill = Color(0x44000000), border = Cosmos.HairlineSoft)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolIcon(Icons.Filled.ArrowBack, "Back", enabled = browser.canGoBack, onClick = onBack)
        ToolIcon(Icons.Filled.ArrowForward, "Forward", enabled = browser.canGoForward, onClick = onForward)
        ToolIcon(Icons.Filled.Refresh, "Reload", onClick = onReload)
        ToolIcon(Icons.Filled.Home, "Home", onClick = onHome)
        OutlinedTextField(
            value = address,
            onValueChange = onAddress,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            placeholder = { Text("/", maxLines = 1) },
        )
        ToolIcon(Icons.Filled.PlayArrow, "Go", onClick = onSubmit)
        ToolIcon(Icons.Filled.Refresh, "Restart runtime", enabled = !busy, onClick = onRestart)
        ToolIcon(Icons.Filled.OpenInBrowser, "Open in browser", onClick = onOpenExternal)
        ToolIcon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Fullscreen", onClick = onFullscreen)
        ToolIcon(Icons.Filled.Stop, "Stop preview", onClick = onStop)
    }
}

@Composable
private fun ToolRail(panel: PreviewPanel, onSelect: (PreviewPanel) -> Unit) {
    Column(
        Modifier.width(72.dp).fillMaxHeight().padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
            .glass(RoundedCornerShape(16.dp), fill = Color(0x66000000), border = Cosmos.HairlineSoft)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RailItem(Icons.Filled.Visibility, "Preview", panel == PreviewPanel.PREVIEW) { onSelect(PreviewPanel.PREVIEW) }
        RailItem(Icons.Filled.Warning, "Problems", panel == PreviewPanel.PROBLEMS) { onSelect(PreviewPanel.PROBLEMS) }
        RailItem(Icons.Filled.Code, "Logs", panel == PreviewPanel.LOGS) { onSelect(PreviewPanel.LOGS) }
        RailItem(Icons.Filled.BugReport, "A11y", panel == PreviewPanel.A11Y) { onSelect(PreviewPanel.A11Y) }
        RailItem(Icons.Filled.Security, "Security", panel == PreviewPanel.SECURITY) { onSelect(PreviewPanel.SECURITY) }
        RailItem(Icons.Filled.OpenInBrowser, "Publish", panel == PreviewPanel.PUBLISH) { onSelect(PreviewPanel.PUBLISH) }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .glass(
                RoundedCornerShape(12.dp),
                fill = if (selected) Color(0x44A88CFF) else Color.Transparent,
                border = if (selected) Color(0x66A88CFF) else Color.Transparent,
            )
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (selected) Cosmos.Violet3 else Cosmos.TextMuted, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) Cosmos.Text else Cosmos.TextMuted, maxLines = 1)
    }
}

@Composable
private fun BottomPanel(
    panel: PreviewPanel,
    diagnostics: PreviewController.Diagnostics,
    console: List<PreviewController.ConsoleEntry>,
    runtimeLogs: String,
    a11y: String?,
    onClear: () -> Unit,
    onRefreshA11y: () -> Unit,
) {
    if (panel == PreviewPanel.PREVIEW) return
    GlassCard(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(panelTitle(panel), style = MaterialTheme.typography.titleSmall, color = Cosmos.Text)
            Spacer(Modifier.weight(1f))
            if (panel == PreviewPanel.A11Y) GhostButton("Refresh", onClick = onRefreshA11y)
            GhostButton("Clear", onClick = onClear)
            Icon(Icons.Filled.Clear, null, tint = Cosmos.TextMuted, modifier = Modifier.size(18.dp).clickable(onClick = onClear))
        }
        LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (panel) {
                PreviewPanel.PROBLEMS -> {
                    val rows = diagnostics.consoleErrors.map { "Console: $it" } + diagnostics.failedRequests.map { "Request: $it" }
                    if (rows.isEmpty()) item { MutedLine("No browser problems captured.") }
                    items(rows) { ProblemLine(it) }
                }
                PreviewPanel.LOGS -> {
                    if (console.isEmpty() && runtimeLogs.isBlank()) item { MutedLine("No runtime or console logs yet.") }
                    if (runtimeLogs.isNotBlank()) item { LogBlock("[Runtime]\n$runtimeLogs") }
                    items(console.takeLast(80)) { LogBlock("[${it.level}] ${it.message} ${if (it.source.isNotBlank()) "(${it.source}:${it.line})" else ""}") }
                }
                PreviewPanel.A11Y -> item { LogBlock(a11y ?: "No accessibility snapshot yet.") }
                PreviewPanel.SECURITY -> item {
                    MutedLine("Security checks watch browser navigation, failed network requests, and console errors. External publishing keys remain in Android Keystore.")
                }
                PreviewPanel.PUBLISH -> item {
                    MutedLine("Publishing hooks are available from Media/Settings. Web preview export and share actions can attach here when the project has a build output.")
                }
                PreviewPanel.PREVIEW -> Unit
            }
        }
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(icon, desc, tint = if (enabled) Cosmos.TextDim else Cosmos.TextMuted.copy(alpha = 0.35f), modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun ProblemLine(text: String) {
    Text(text, color = Cosmos.Red, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun MutedLine(text: String) {
    Text(text, color = Cosmos.TextMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LogBlock(text: String) {
    Text(
        text,
        color = Cosmos.TextDim,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = 80,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun panelTitle(panel: PreviewPanel) = when (panel) {
    PreviewPanel.PREVIEW -> "Preview"
    PreviewPanel.PROBLEMS -> "Problems"
    PreviewPanel.LOGS -> "System Messages"
    PreviewPanel.A11Y -> "Accessibility"
    PreviewPanel.SECURITY -> "Security"
    PreviewPanel.PUBLISH -> "Publish"
}
