package com.legion.orion.internet

import com.legion.orion.engine.agent.tools.PathJail
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Shared Internet access for every AI engine in Orion (Chat/BUILD agent tools, Claude Code,
 * Codex, Grok via `orion-tool`).
 *
 * Phase 1 capabilities:
 * - [search] web research (DuckDuckGo HTML — no API key)
 * - [fetch] readable page text / raw HTML / JSON for docs and research
 * - [download] stream assets into the active project (images, zips, fonts, models, …)
 *
 * Phase 2+ (scaffolded, not fully automated yet): interactive WebView sessions, credential vault,
 * and user-owned mailbox OTP assist for account logins. See [DevIdentityStore].
 */
@Singleton
class InternetManager @Inject constructor(
    private val http: OkHttpClient,
) {
    private val client: OkHttpClient = http.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class SearchHit(
        val title: String,
        val url: String,
        val snippet: String,
    )

    data class FetchResult(
        val url: String,
        val finalUrl: String,
        val statusCode: Int,
        val contentType: String,
        val title: String?,
        val text: String,
        val truncated: Boolean,
        val byteLength: Int,
    )

    data class DownloadResult(
        val url: String,
        val finalUrl: String,
        val statusCode: Int,
        val contentType: String,
        val path: File,
        val relativePath: String,
        val bytes: Long,
    )

    suspend fun search(query: String, maxResults: Int = 8): Result<List<SearchHit>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = query.trim()
            require(q.isNotEmpty()) { "Search query is empty" }
            val limit = maxResults.coerceIn(1, 15)
            val body = "q=${urlEncode(q)}&kl=wt-wt"
            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(body.toRequestBody(FORM_URLENCODED))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Search failed: HTTP ${response.code}")
                val html = response.body?.string().orEmpty()
                parseDuckDuckGoHtml(html, limit)
            }
        }
    }

    /**
     * Fetch a URL and return readable content. Defaults to plain-text extraction for HTML pages so
     * models get research-ready text rather than raw markup.
     *
     * @param mode `text` (default) | `html` | `raw`
     */
    suspend fun fetch(
        url: String,
        mode: String = "text",
        maxChars: Int = DEFAULT_FETCH_CHARS,
    ): Result<FetchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val target = normalizeHttpUrl(url)
            val request = Request.Builder()
                .url(target)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (bytes.size > MAX_FETCH_BYTES) {
                    error("Response too large (${bytes.size} bytes; max $MAX_FETCH_BYTES). Use web_download for assets.")
                }
                val contentType = response.header("Content-Type").orEmpty()
                val charset = charsetFromContentType(contentType)
                val raw = String(bytes, charset)
                val finalUrl = response.request.url.toString()
                val title = extractTitle(raw)
                val want = mode.lowercase(Locale.US)
                val textBody = when {
                    want == "html" || want == "raw" -> raw
                    contentType.contains("json", ignoreCase = true) -> raw
                    contentType.contains("html", ignoreCase = true) || looksLikeHtml(raw) -> htmlToText(raw)
                    else -> raw
                }
                val cap = maxChars.coerceIn(1_000, MAX_FETCH_CHARS)
                val truncated = textBody.length > cap
                FetchResult(
                    url = target,
                    finalUrl = finalUrl,
                    statusCode = response.code,
                    contentType = contentType.ifBlank { "unknown" },
                    title = title,
                    text = if (truncated) textBody.take(cap) else textBody,
                    truncated = truncated,
                    byteLength = bytes.size,
                )
            }
        }
    }

    /**
     * Download a remote asset into the project directory. Path is jailed; size capped.
     * Suitable for images, zips, fonts, free-model weights (within cap), CSS/JS libraries, etc.
     */
    suspend fun download(
        url: String,
        projectDir: File,
        relativePath: String,
        maxBytes: Long = DEFAULT_DOWNLOAD_BYTES,
    ): Result<DownloadResult> = withContext(Dispatchers.IO) {
        runCatching {
            val target = normalizeHttpUrl(url)
            val dest = PathJail.resolve(projectDir, relativePath)
            if (dest.extension.isBlank()) {
                error("Destination must include a file extension (got '$relativePath')")
            }
            dest.parentFile?.mkdirs()
            val request = Request.Builder()
                .url(target)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
                val body = response.body ?: error("Empty response body")
                val declared = body.contentLength()
                val cap = maxBytes.coerceIn(1L, MAX_DOWNLOAD_BYTES)
                if (declared > 0 && declared > cap) {
                    error("Remote file is ${declared} bytes; max allowed is $cap. Raise max_bytes or pick a smaller asset.")
                }
                val contentType = response.header("Content-Type").orEmpty()
                var written = 0L
                dest.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            written += n
                            if (written > cap) {
                                dest.delete()
                                error("Download exceeded max_bytes ($cap). Partial file deleted.")
                            }
                            out.write(buf, 0, n)
                        }
                    }
                }
                DownloadResult(
                    url = target,
                    finalUrl = response.request.url.toString(),
                    statusCode = response.code,
                    contentType = contentType.ifBlank { "application/octet-stream" },
                    path = dest,
                    relativePath = dest.relativeTo(projectDir.canonicalFile).invariantSeparatorsPath,
                    bytes = written,
                )
            }
        }
    }

    fun formatSearch(hits: List<SearchHit>): String {
        if (hits.isEmpty()) return "No search results."
        return hits.mapIndexed { i, h ->
            "${i + 1}. ${h.title}\n   URL: ${h.url}\n   ${h.snippet}"
        }.joinToString("\n\n")
    }

    fun formatFetch(r: FetchResult): String = buildString {
        appendLine("url: ${r.url}")
        if (r.finalUrl != r.url) appendLine("final_url: ${r.finalUrl}")
        appendLine("status: ${r.statusCode}")
        appendLine("content_type: ${r.contentType}")
        r.title?.let { appendLine("title: $it") }
        appendLine("bytes: ${r.byteLength}${if (r.truncated) " (text truncated)" else ""}")
        appendLine("---")
        append(r.text)
    }

    fun formatDownload(r: DownloadResult): String =
        "Downloaded ${r.bytes} bytes → ${r.relativePath}\n" +
            "url: ${r.finalUrl}\n" +
            "status: ${r.statusCode}\n" +
            "content_type: ${r.contentType}"

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun normalizeHttpUrl(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "URL is empty" }
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> "https://$trimmed"
        }
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            throw IOException("Invalid URL: $raw", e)
        }
        val scheme = uri.scheme?.lowercase(Locale.US)
        require(scheme == "http" || scheme == "https") { "Only http/https URLs are allowed (got $scheme)" }
        require(!uri.host.isNullOrBlank()) { "URL has no host" }
        val host = uri.host.lowercase(Locale.US)
        // Block local/metadata targets — research tools must not be used for on-device SSRF.
        if (isPrivateOrLocalHost(host)) {
            error("Refusing private/local URL host: $host")
        }
        return withScheme
    }

    private fun isPrivateOrLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") ||
            host == "127.0.0.1" || host == "0.0.0.0" || host == "::1" ||
            host == "metadata.google.internal" ||
            host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")
        ) {
            return true
        }
        if (host.startsWith("172.")) {
            val second = host.split('.').getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }

    private fun parseDuckDuckGoHtml(html: String, limit: Int): List<SearchHit> {
        // DuckDuckGo HTML layout: result blocks with class result__a (title link) + result__snippet.
        val results = ArrayList<SearchHit>(limit)
        val blockRegex = Regex(
            """(?is)<a[^>]+class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        )
        val snippetRegex = Regex("""(?is)class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(?:a|td|div)""")
        val blocks = blockRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        for ((index, match) in blocks.withIndex()) {
            if (results.size >= limit) break
            val href = decodeDuckDuckGoRedirect(match.groupValues[1])
            val title = stripTags(match.groupValues[2]).ifBlank { href }
            if (href.isBlank() || href.contains("duckduckgo.com/y.js")) continue
            val snippet = snippets.getOrNull(index).orEmpty().take(280)
            results += SearchHit(title = title, url = href, snippet = snippet)
        }
        // Fallback: looser anchors if the layout changed.
        if (results.isEmpty()) {
            val loose = Regex("""(?is)<a[^>]+href="(https?://[^"]+)"[^>]*>(.*?)</a>""")
            for (m in loose.findAll(html)) {
                if (results.size >= limit) break
                val href = m.groupValues[1]
                if (href.contains("duckduckgo.com")) continue
                results += SearchHit(
                    title = stripTags(m.groupValues[2]).ifBlank { href },
                    url = href,
                    snippet = "",
                )
            }
        }
        return results
    }

    private fun decodeDuckDuckGoRedirect(href: String): String {
        // DDG wraps links as //duckduckgo.com/l/?uddg=<encoded>
        val decoded = href
            .replace("&amp;", "&")
            .replace("&#x2F;", "/")
            .replace("&#47;", "/")
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(decoded)?.groupValues?.getOrNull(1)
        return if (uddg != null) {
            try {
                java.net.URLDecoder.decode(uddg, Charsets.UTF_8.name())
            } catch (_: Exception) {
                decoded
            }
        } else if (decoded.startsWith("//")) {
            "https:$decoded"
        } else {
            decoded
        }
    }

    private fun htmlToText(html: String): String {
        var s = html
        // Drop scripts/styles/noscript
        s = s.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        s = s.replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        s = s.replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        // Line breaks for block tags
        s = s.replace(Regex("(?i)<(br|hr)\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|h[1-6]|li|tr|section|article|header|footer|blockquote)>"), "\n")
        s = s.replace(Regex("(?i)<li[^>]*>"), "• ")
        s = stripTags(s)
        s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun stripTags(html: String): String =
        html.replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("&#(\\d+);")) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: " "
            }
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun extractTitle(html: String): String? =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.getOrNull(1)?.let { stripTags(it) }

    private fun looksLikeHtml(s: String): Boolean {
        val head = s.take(256).lowercase(Locale.US)
        return head.contains("<!doctype html") || head.contains("<html") || head.contains("<head")
    }

    private fun charsetFromContentType(contentType: String): Charset {
        val m = Regex("""charset=([\\w\\-]+)""", RegexOption.IGNORE_CASE).find(contentType)
        val name = m?.groupValues?.getOrNull(1)
        return try {
            if (name != null) Charset.forName(name) else Charsets.UTF_8
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8.name())

    companion object {
        private const val USER_AGENT =
            "OrionBuilder/0.1 (Android; research agent; +https://github.com/legion-orion)"
        private val FORM_URLENCODED = "application/x-www-form-urlencoded".toMediaType()

        private const val CONNECT_TIMEOUT_SEC = 20L
        private const val READ_TIMEOUT_SEC = 90L
        private const val WRITE_TIMEOUT_SEC = 90L

        const val DEFAULT_FETCH_CHARS = 24_000
        const val MAX_FETCH_CHARS = 80_000
        const val MAX_FETCH_BYTES = 2 * 1024 * 1024 // 2 MB for in-memory fetch
        const val DEFAULT_DOWNLOAD_BYTES = 50L * 1024 * 1024 // 50 MB
        const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024 // 200 MB hard cap
    }
}
