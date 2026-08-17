package com.legion.orion

import androidx.test.platform.app.InstrumentationRegistry
import com.legion.orion.internet.InternetManager
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * On-device gate for Internet Manager Phase 1: search, fetch, download over real mobile network.
 *
 *   adb shell am instrument -w \
 *     -e class com.legion.orion.InternetManagerDeviceTest \
 *     com.legion.orion.debug.test/androidx.test.runner.AndroidJUnitRunner
 */
class InternetManagerDeviceTest {

    private val internet = InternetManager(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build(),
    )

    @Test
    fun webSearchReturnsHits() = runBlocking {
        val hits = internet.search("Kotlin coroutines documentation", maxResults = 5)
            .getOrElse { fail("web_search failed: ${it.message}"); return@runBlocking }
        assertTrue("expected at least 1 search hit, got ${hits.size}", hits.isNotEmpty())
        hits.forEach { hit ->
            assertTrue("empty title", hit.title.isNotBlank())
            assertTrue("bad url: ${hit.url}", hit.url.startsWith("http"))
        }
        println("SEARCH_OK count=${hits.size} first=${hits.first().title} ${hits.first().url}")
    }

    @Test
    fun webFetchReturnsReadableText() = runBlocking {
        val page = internet.fetch("https://example.com", mode = "text", maxChars = 8_000)
            .getOrElse { fail("web_fetch failed: ${it.message}"); return@runBlocking }
        assertEquals(200, page.statusCode)
        assertTrue("expected Example Domain body", page.text.contains("Example Domain", ignoreCase = true))
        assertFalse("should not be pure HTML for mode=text", page.text.trimStart().startsWith("<!"))
        println("FETCH_OK status=${page.statusCode} title=${page.title} chars=${page.text.length}")
    }

    @Test
    fun webDownloadWritesIntoProjectDir() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val project = File(ctx.filesDir, "projects/internet-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val destRel = "assets/example.html"
        val result = internet.download(
            url = "https://example.com",
            projectDir = project,
            relativePath = destRel,
            maxBytes = 1_000_000,
        ).getOrElse { fail("web_download failed: ${it.message}"); return@runBlocking }

        assertTrue("file missing: ${result.path}", result.path.isFile)
        assertTrue("empty download", result.bytes > 0)
        assertEquals(destRel, result.relativePath.replace('\\', '/'))
        val body = result.path.readText()
        assertTrue("downloaded body unexpected", body.contains("Example Domain", ignoreCase = true))
        println("DOWNLOAD_OK bytes=${result.bytes} path=${result.relativePath}")
    }

    @Test
    fun rejectsPrivateHosts() = runBlocking {
        val r = internet.fetch("http://127.0.0.1/")
        assertTrue("127.0.0.1 should be refused", r.isFailure)
        println("SSRF_BLOCK_OK: ${r.exceptionOrNull()?.message}")
    }
}
