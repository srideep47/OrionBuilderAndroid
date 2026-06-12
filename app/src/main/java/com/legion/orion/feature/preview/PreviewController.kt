package com.legion.orion.feature.preview

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.webkit.WebView
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Bridges the agent's QA tools (which run on a background thread) to the live preview WebView
 * (which must be touched on the main thread). The preview composable attaches its WebView and
 * feeds console errors / failed requests here; tools read diagnostics and capture screenshots.
 */
@Singleton
class PreviewController @Inject constructor() {

    private var webViewRef: WeakReference<WebView>? = null
    private val consoleErrors = CopyOnWriteArrayList<String>()
    private val failedRequests = CopyOnWriteArrayList<String>()

    fun attach(webView: WebView) { webViewRef = WeakReference(webView) }
    fun detach() { webViewRef = null }

    fun recordConsoleError(message: String) { consoleErrors.add(message.take(500)) }
    fun recordFailedRequest(entry: String) { failedRequests.add(entry.take(300)) }
    fun clearDiagnostics() { consoleErrors.clear(); failedRequests.clear() }

    fun diagnostics(): Diagnostics = Diagnostics(consoleErrors.toList(), failedRequests.toList())

    /**
     * Renders the current WebView content to a PNG file. Uses PixelCopy (captures the GPU-composited
     * surface — required because the preview WebView is hardware-accelerated) and falls back to a
     * software [View.draw] only if no host Window is reachable. Returns true on success.
     */
    suspend fun screenshot(dest: File): Boolean = withContext(Dispatchers.Main) {
        val wv = webViewRef?.get() ?: return@withContext false
        if (wv.width == 0 || wv.height == 0) return@withContext false
        val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)

        val captured = when (val window = wv.hostWindow()) {
            null -> runCatching { wv.draw(Canvas(bmp)) }.isSuccess  // last-resort software draw
            else -> suspendCancellableCoroutine { cont ->
                val loc = IntArray(2).also { wv.getLocationInWindow(it) }
                val src = Rect(loc[0], loc[1], loc[0] + wv.width, loc[1] + wv.height)
                runCatching {
                    PixelCopy.request(window, src, bmp, { result ->
                        cont.resume(result == PixelCopy.SUCCESS)
                    }, Handler(Looper.getMainLooper()))
                }.onFailure { cont.resume(false) }
            }
        }
        if (!captured) return@withContext false
        runCatching {
            dest.parentFile?.mkdirs()
            dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }.isSuccess
    }

    private fun View.hostWindow(): Window? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx.window
            ctx = ctx.baseContext
        }
        return null
    }

    /** Walks the rendered DOM into a compact accessibility outline via injected JS. */
    suspend fun accessibilityTree(): String? = withContext(Dispatchers.Main) {
        val wv = webViewRef?.get() ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(A11Y_JS) { result ->
                cont.resume(result?.trim('"')?.replace("\\n", "\n")?.replace("\\\"", "\""))
            }
        }
    }

    val isAttached: Boolean get() = webViewRef?.get() != null

    data class Diagnostics(val consoleErrors: List<String>, val failedRequests: List<String>)

    private companion object {
        // Compact role/name/text outline of interactive + landmark elements.
        const val A11Y_JS = """
        (function(){
          function label(el){
            return el.getAttribute('aria-label') || el.getAttribute('alt') ||
                   (el.innerText||'').trim().slice(0,40);
          }
          var sel = 'h1,h2,h3,button,a,input,nav,main,header,footer,[role]';
          var out = [];
          document.querySelectorAll(sel).forEach(function(el){
            var role = el.getAttribute('role') || el.tagName.toLowerCase();
            out.push(role + ': ' + label(el));
          });
          return out.slice(0,200).join('\n');
        })();
        """
    }
}
