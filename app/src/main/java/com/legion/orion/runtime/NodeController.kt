package com.legion.orion.runtime

import android.content.Context
import android.util.Log
import com.legion.orion.runtime.node.NodeRuntimeService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Main-process controller for the embedded Node runtime. Extracts the bundled node assets (host
 * script + npm) on first use, starts the :node foreground service, and drives it over a localhost
 * JSON-RPC channel. Because Node runs in a separate process, all calls are best-effort HTTP.
 */
@Singleton
class NodeController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val startMutex = Mutex()

    @Volatile private var started = false

    private val nodeDir: File get() = File(context.filesDir, "node")
    private val base = "http://127.0.0.1:$CONTROL_PORT"

    /**
     * Extracts bundled npm/host assets without starting the :node process. Used by first-launch
     * [com.legion.orion.runtime.RuntimeBootstrap] so tooling is on disk before the user opens Preview.
     */
    suspend fun ensureAssetsExtracted() = withContext(Dispatchers.IO) {
        AssetExtractor.extractOnce(context, "node", nodeDir, version = ASSET_VERSION)
    }

    /** Extracts assets, launches the :node service, and waits for the host to report healthy. */
    suspend fun ensureStarted(): Boolean = startMutex.withLock {
        if (started && health()) return@withLock true
        withContext(Dispatchers.IO) {
            AssetExtractor.extractOnce(context, "node", nodeDir, version = ASSET_VERSION)
        }
        val hostScript = File(nodeDir, "orion-host.js").absolutePath
        val npmCli = File(nodeDir, "npm/bin/npm-cli.js").absolutePath
        NodeRuntimeService.start(context, hostScript, arrayListOf(CONTROL_PORT.toString(), npmCli))

        // Node boot + bind can take a few seconds on cold start.
        repeat(40) {
            if (health()) { started = true; return@withLock true }
            delay(500)
        }
        Log.w(TAG, "Node host did not become healthy in time (last error: ${lastHealthError?.toString()})")
        false
    }

    fun stop() {
        NodeRuntimeService.stop(context)
        started = false
    }

    @Volatile private var lastHealthError: Throwable? = null

    /** Main-safe: callers may be on the UI dispatcher (e.g. the Network toggle). */
    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$base/health").build()).execute().use { it.isSuccessful }
        }.onFailure { lastHealthError = it }.getOrDefault(false)
    }

    /** Runs `npm ...` or `node -e ...` in the host process. Returns (exitCode, combinedOutput). */
    suspend fun exec(argv: List<String>, cwd: String): Pair<Int, String> {
        ensureStarted()
        val body = buildJsonObject {
            putJsonArray("argv") { argv.forEach { add(it) } }
            put("cwd", cwd)
        }
        val resp = post("/exec", body)
        val code = resp["code"]?.jsonPrimitive?.intOrNull ?: -1
        val out = resp["output"]?.jsonPrimitive?.contentOrNull ?: ""
        return code to out
    }

    suspend fun startDevServer(dir: String, port: Int = 0): Int {
        ensureStarted()
        val resp = post("/devserver/start", buildJsonObject { put("dir", dir); put("port", port) })
        return resp["port"]?.jsonPrimitive?.intOrNull ?: -1
    }

    suspend fun stopDevServer(port: Int) {
        post("/devserver/stop", buildJsonObject { put("port", port) })
    }

    suspend fun devServerLogs(port: Int): String {
        val resp = post("/devserver/logs", buildJsonObject { put("port", port) })
        return resp["logs"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private suspend fun post(path: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base$path")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            runCatching { json.parseToJsonElement(text) as JsonObject }.getOrElse { JsonObject(emptyMap()) }
        }
    }

    companion object {
        private const val TAG = "NodeController"
        const val CONTROL_PORT = 8137
        private const val ASSET_VERSION = "node18-npm1082-babel724-p2p-v10-react-peer-pins"
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
