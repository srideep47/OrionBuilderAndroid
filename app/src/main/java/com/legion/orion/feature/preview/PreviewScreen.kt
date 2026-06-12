package com.legion.orion.feature.preview

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(vm: PreviewViewModel = hiltViewModel()) {
    val server by vm.devServer.collectAsStateWithLifecycle()

    if (server == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No preview running", style = MaterialTheme.typography.titleMedium)
            Text(
                "Run the agent with start_dev_server, or start it from Build.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val controller = vm.previewController
    val url = server!!.url

    DisposableEffect(url) {
        controller.clearDiagnostics()
        onDispose { controller.detach() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                        if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            controller.recordConsoleError("${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                        }
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                        if (req.isForMainFrame || true) {
                            controller.recordFailedRequest("${err.errorCode} ${req.url}")
                        }
                    }
                    override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, resp: WebResourceResponse) {
                        if (resp.statusCode >= 400) controller.recordFailedRequest("${resp.statusCode} ${req.url}")
                    }
                }
                controller.attach(this)
                loadUrl(url)
            }
        },
        update = { it.loadUrl(url) },
    )
}
