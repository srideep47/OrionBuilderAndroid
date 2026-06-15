package com.legion.orion.feature.preview

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legion.orion.core.design.Cosmos
import com.legion.orion.core.design.Liquid
import com.legion.orion.core.design.LiquidButton
import com.legion.orion.core.design.LiquidChip
import com.legion.orion.core.design.LiquidIconButton
import com.legion.orion.core.design.LocalBottomChromeHeight
import com.legion.orion.core.design.MonoFamily
import com.legion.orion.core.design.liquidGlass

private enum class DeviceMode(val label: String, val width: Int?) {
    FIT("Fit", null), PHONE("390", 390), TABLET("768", 768)
}

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
    var device by rememberSaveable { mutableStateOf(DeviceMode.FIT) }

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
        if (!fullscreen) {
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
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val widthMod = device.width?.let { Modifier.width(it.dp) } ?: Modifier.fillMaxWidth()
            AndroidView(
                modifier = widthMod.fillMaxHeight(),
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

            if (fullscreen) {
                LiquidIconButton(
                    Icons.Outlined.FullscreenExit,
                    "Exit fullscreen",
                    vm::toggleFullscreen,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    size = 34.dp,
                )
            }
        }

        if (!fullscreen) {
            // Bottom dock: panel chips + device modes + (when a drawer panel is
            // active) the panel content. Padded clear of the floating tab bar so
            // nothing is obscured, and the WebView above keeps the maximum area.
            Column(Modifier.padding(bottom = LocalBottomChromeHeight.current)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        PreviewPanel.PREVIEW to "Preview",
                        PreviewPanel.PROBLEMS to "Problems",
                        PreviewPanel.LOGS to "Logs",
                        PreviewPanel.A11Y to "A11y",
                        PreviewPanel.SECURITY to "Security",
                        PreviewPanel.PUBLISH to "Publish",
                    ).forEach { (p, label) ->
                        LiquidChip(label, selected = panel == p, onClick = { vm.setPanel(p) })
                    }
                    Spacer(Modifier.width(8.dp))
                    DeviceMode.entries.forEach { d ->
                        LiquidChip(d.label, selected = device == d, onClick = { device = d })
                    }
                }

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
        LiquidButton("Run project preview", onClick = onStart, enabled = !busy, icon = Icons.Outlined.PlayArrow)
        if (busy) CircularProgressIndicator(Modifier.padding(top = 14.dp), color = Cosmos.Violet)
        error?.let {
            Text(it, color = Cosmos.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
        }
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
    val focusManager = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .liquidGlass(shape = RoundedCornerShape(16.dp), fillTopAlpha = 0.08f)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Leading nav icons collapse away while editing the address so the
            // field can take the full width and the URL stays readable.
            AnimatedVisibility(visible = !editing) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ToolIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", enabled = browser.canGoBack, onClick = onBack)
                    ToolIcon(Icons.AutoMirrored.Outlined.ArrowForward, "Forward", enabled = browser.canGoForward, onClick = onForward)
                    ToolIcon(Icons.Outlined.Refresh, "Reload", onClick = onReload)
                    ToolIcon(Icons.Outlined.Home, "Home", onClick = onHome)
                }
            }

            Row(
                Modifier
                    .weight(1f)
                    .clip(Liquid.ShapePill)
                    .background(Color(0x4D000000))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                BasicTextField(
                    value = address,
                    onValueChange = onAddress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { editing = it.isFocused },
                    textStyle = MaterialTheme.typography.labelMedium.copy(color = Cosmos.TextDim, fontFamily = MonoFamily),
                    cursorBrush = SolidColor(Cosmos.Violet),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onSubmit(); focusManager.clearFocus() }),
                )
            }

            if (editing) {
                // While editing: just a Go/Done that submits and dismisses.
                ToolIcon(Icons.Outlined.PlayArrow, "Go", onClick = { onSubmit(); focusManager.clearFocus() })
            } else {
                ToolIcon(Icons.Outlined.PlayArrow, "Go", onClick = onSubmit)
                ToolIcon(Icons.Outlined.RestartAlt, "Restart runtime", enabled = !busy, onClick = onRestart)
                ToolIcon(Icons.Outlined.OpenInBrowser, "Open in browser", onClick = onOpenExternal)
                ToolIcon(
                    if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    "Fullscreen",
                    onClick = onFullscreen,
                )
                ToolIcon(Icons.Outlined.Stop, "Stop preview", onClick = onStop)
            }
        }
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
    var logFilter by rememberSaveable { mutableStateOf("All") }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 260.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .liquidGlass(shape = RoundedCornerShape(16.dp), fillTopAlpha = 0.07f)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(panelTitle(panel), style = MaterialTheme.typography.titleSmall, color = Cosmos.Text)
            Spacer(Modifier.weight(1f))
            if (panel == PreviewPanel.LOGS) {
                listOf("All", "Error", "Warn").forEach { f ->
                    LiquidChip(f, selected = logFilter == f, onClick = { logFilter = f })
                }
            }
            if (panel == PreviewPanel.A11Y) {
                LiquidChip("Refresh", onClick = onRefreshA11y)
            }
            LiquidChip("Clear", onClick = onClear)
        }
        LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (panel) {
                PreviewPanel.PROBLEMS -> {
                    val rows = diagnostics.consoleErrors.map { "Console: $it" } +
                        diagnostics.failedRequests.map { "Request: $it" }
                    if (rows.isEmpty()) item { MutedLine("No browser problems captured.") }
                    items(rows) { ProblemLine(it) }
                }
                PreviewPanel.LOGS -> {
                    val filtered = console.filter {
                        when (logFilter) {
                            "Error" -> it.level.contains("ERROR", ignoreCase = true)
                            "Warn" -> it.level.contains("WARN", ignoreCase = true)
                            else -> true
                        }
                    }
                    if (filtered.isEmpty() && runtimeLogs.isBlank()) item { MutedLine("No runtime or console logs yet.") }
                    if (runtimeLogs.isNotBlank() && logFilter == "All") item { LogBlock("[Runtime]\n$runtimeLogs") }
                    items(filtered.takeLast(80)) {
                        LogBlock("[${it.level}] ${it.message} ${if (it.source.isNotBlank()) "(${it.source}:${it.line})" else ""}")
                    }
                }
                PreviewPanel.A11Y -> item { LogBlock(a11y ?: "No accessibility snapshot yet.") }
                PreviewPanel.SECURITY -> item {
                    MutedLine(
                        "Security checks watch browser navigation, failed network requests, and console errors. " +
                            "External publishing keys remain in Android Keystore.",
                    )
                }
                PreviewPanel.PUBLISH -> item {
                    MutedLine("Publishing hooks live in Hub → Publish. Web preview export and share actions attach here when the project has a build output.")
                }
                PreviewPanel.PREVIEW -> Unit
            }
        }
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(Liquid.ShapePill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            desc,
            tint = if (enabled) Cosmos.TextDim else Cosmos.TextMuted.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp),
        )
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
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
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
