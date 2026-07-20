package com.legion.orion.runtime

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Main-process client for the GDExtension AI-control bridge (`OrionBridgeServer`, see
 * `runtime/godot/bridge-native/`) running inside the isolated `:godot` process. Raw
 * NDJSON-over-loopback-TCP, mirroring [NodeController]'s and `ClaudeToolBridge`'s wire-protocol
 * shape — the bridge only has Godot's `TCPServer`/`StreamPeerTCP` available (no HTTP stack), so
 * this speaks the same simple line-delimited-JSON framing rather than wrapping it in HTTP.
 *
 * The v1 read-only ops (`ping`/`scene_tree`/`perf_snapshot`/`screenshot`) each get a small typed
 * convenience method, used by the Engine debug UI. G4's mutating ops (`get_property`/`set_property`/
 * `call_method`/`list_properties`/`list_methods`/`create_node`/`delete_node`/`set_paused`/`step`/
 * `simulate_input`/`reload_script`, see `orion_bridge_server_ops.cpp`) go through the generic [call]
 * instead of one bespoke wrapper each — the AI tool layer (`GodotAgentTools`/`ClaudeToolBridge`, G5)
 * needs the *raw* response (including `error` messages on failure) to surface back to the LLM
 * verbatim, not a Kotlin-typed nullable that throws the "why" away, and a dozen near-identical
 * wrappers here would just duplicate what each tool's own schema/description already documents.
 *
 * The auth token isn't passed in — it's generated fresh by the bridge on every `:godot` process
 * start and written to `user://orion_bridge_token.txt`, which is the same physical path as
 * `context.filesDir` (confirmed on-device: both processes share the app's UID/filesystem).
 */
@Singleton
class GodotController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun ping(): Boolean =
        request(buildJsonObject { put("action", "ping") })?.get("ok")?.jsonPrimitive?.boolean == true

    suspend fun sceneTree(): JsonObject? = requestOk("scene_tree")?.get("tree")?.jsonObject

    suspend fun perfSnapshot(): JsonObject? = requestOk("perf_snapshot")?.get("perf")?.jsonObject

    /** Returns the absolute on-device path of the captured screenshot PNG, or null on failure. */
    suspend fun screenshot(): String? =
        requestOk("screenshot")?.get("path")?.jsonPrimitive?.contentOrNull

    /**
     * Generic entry point for every G4 mutating op — see `orion_bridge_server_ops.cpp`'s `_dispatch`
     * routing for the full action list and each op's expected [params]/response shape (also
     * documented per-tool in `GodotAgentTools`/`ClaudeToolBridge`, G5). Returns the *raw* response
     * object (containing `ok`, and either the op's data or an `error` string) rather than unwrapping
     * it, since callers here are surfacing results straight to an LLM tool call, error message
     * included — not consuming a typed Kotlin value. Returns null only on a transport-level failure
     * (bridge unreachable, timeout, malformed response), distinct from a well-formed `{"ok": false,
     * "error": "..."}` op-level failure.
     */
    suspend fun call(action: String, params: JsonObject = JsonObject(emptyMap())): JsonObject? =
        request(
            buildJsonObject {
                put("action", action)
                params.forEach { (k, v) -> put(k, v) }
            },
        )

    private suspend fun requestOk(action: String): JsonObject? =
        request(buildJsonObject { put("action", action) })
            ?.takeIf { it["ok"]?.jsonPrimitive?.boolean == true }

    private suspend fun request(body: JsonObject): JsonObject? = withContext(Dispatchers.IO) {
        val token = readToken() ?: return@withContext null
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", PORT), TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS
                val fullRequest = buildJsonObject {
                    body.forEach { (k, v) -> put(k, v) }
                    put("token", token)
                }
                socket.getOutputStream().write((fullRequest.toString() + "\n").toByteArray())
                socket.getOutputStream().flush()
                val line = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                line?.let { Json.parseToJsonElement(it).jsonObject }
            }
        }.onFailure { Log.w(TAG, "bridge request failed", it) }.getOrNull()
    }

    private fun readToken(): String? =
        runCatching { File(context.filesDir, "orion_bridge_token.txt").readText().trim() }.getOrNull()

    private companion object {
        const val TAG = "GodotController"
        const val PORT = 8139
        const val TIMEOUT_MS = 5000
    }
}
