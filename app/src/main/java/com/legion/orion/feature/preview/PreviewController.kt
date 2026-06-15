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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val consoleEntries = CopyOnWriteArrayList<ConsoleEntry>()

    private val _browserState = MutableStateFlow(BrowserState())
    val browserState = _browserState.asStateFlow()

    fun attach(webView: WebView) {
        webViewRef = WeakReference(webView)
        updateBrowserState(webView)
    }
    fun detach() { webViewRef = null }

    fun recordConsole(level: String, message: String, source: String, line: Int) {
        val entry = ConsoleEntry(level, message.take(500), source.takeLast(80), line, System.currentTimeMillis())
        consoleEntries.add(entry)
        while (consoleEntries.size > 200) consoleEntries.removeAt(0)
        if (level.equals("ERROR", ignoreCase = true)) consoleErrors.add("${entry.message} (${entry.source}:${entry.line})")
    }

    fun recordConsoleError(message: String) { recordConsole("ERROR", message, "", 0) }
    fun recordFailedRequest(entry: String) { failedRequests.add(entry.take(300)) }
    fun clearDiagnostics() { consoleErrors.clear(); failedRequests.clear(); consoleEntries.clear() }

    fun diagnostics(): Diagnostics = Diagnostics(consoleErrors.toList(), failedRequests.toList())

    fun consoleLog(): List<ConsoleEntry> = consoleEntries.toList()

    fun updateBrowserState(webView: WebView? = webViewRef?.get()) {
        if (webView == null) return
        _browserState.value = BrowserState(
            url = webView.url.orEmpty(),
            title = webView.title.orEmpty(),
            canGoBack = webView.canGoBack(),
            canGoForward = webView.canGoForward(),
            progress = webView.progress,
        )
    }

    fun goBack() { webViewRef?.get()?.takeIf { it.canGoBack() }?.goBack() }
    fun goForward() { webViewRef?.get()?.takeIf { it.canGoForward() }?.goForward() }
    fun reload() { webViewRef?.get()?.reload() }
    fun loadUrl(url: String) { webViewRef?.get()?.loadUrl(url) }

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

    /** Runs arbitrary JS in the preview and returns its (string) result, unwrapped. */
    suspend fun eval(js: String): String? = withContext(Dispatchers.Main) {
        val wv = webViewRef?.get() ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            runCatching {
                wv.evaluateJavascript(js) { result -> cont.resume(decodeJsResult(result)) }
            }.onFailure { cont.resume(null) }
        }
    }

    /**
     * Reports whether the rendered page is actually styled — the signal the QA gate
     * can't get from console/network alone (a stylesheet served with the wrong MIME,
     * a wrong href, or a missing <link> all yield a silently unstyled page). Returns
     * a compact JSON string.
     */
    suspend fun renderReport(): String? = eval(RENDER_REPORT_JS)

    /** Unwraps a value returned by WebView.evaluateJavascript (JSON-encoded). */
    private fun decodeJsResult(result: String?): String? {
        if (result == null || result == "null") return null
        return if (result.length >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
            result.substring(1, result.length - 1)
                .replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t")
                .replace("\\\\", "\\")
        } else result
    }

    val isAttached: Boolean get() = webViewRef?.get() != null

    data class Diagnostics(val consoleErrors: List<String>, val failedRequests: List<String>)
    data class ConsoleEntry(val level: String, val message: String, val source: String, val line: Int, val at: Long)
    data class BrowserState(
        val url: String = "",
        val title: String = "",
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val progress: Int = 0,
    )

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

        // Render-health probe: stylesheet load state + computed body styles, so the
        // agent can tell a styled page from a silently-unstyled one.
        const val RENDER_REPORT_JS = """
        (function(){
          try {
            var sheets = Array.prototype.slice.call(document.styleSheets || []);
            var ruleCount = 0;
            sheets.forEach(function(s){ try { ruleCount += (s.cssRules ? s.cssRules.length : 0); } catch(e){} });
            var links = Array.prototype.slice.call(document.querySelectorAll('link[rel~="stylesheet"]')).map(function(l){
              return { href: l.getAttribute('href'), loaded: !!l.sheet, rules: (l.sheet && l.sheet.cssRules ? l.sheet.cssRules.length : 0) };
            });
            var styleTags = document.querySelectorAll('style').length;
            var body = document.body;
            var cs = body ? getComputedStyle(body) : null;
            var r = {
              title: document.title,
              url: location.href,
              elements: document.getElementsByTagName('*').length,
              styleSheets: sheets.length,
              styleTags: styleTags,
              cssRules: ruleCount,
              links: links,
              bodyBackground: cs ? cs.backgroundColor : null,
              bodyColor: cs ? cs.color : null,
              bodyFontFamily: cs ? cs.fontFamily : null,
              hasStyles: ruleCount > 0,
              textSample: (body && body.innerText ? body.innerText.trim().slice(0,160) : '')
            };
            return JSON.stringify(r);
          } catch(e) { return JSON.stringify({ error: String(e) }); }
        })();
        """
    }
}
