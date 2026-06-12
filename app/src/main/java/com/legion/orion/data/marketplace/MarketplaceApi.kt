package com.legion.orion.data.marketplace

import com.legion.orion.core.common.Dispatcher
import com.legion.orion.core.common.OrionDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin clients for the HuggingFace and ModelScope public REST APIs. Parsed
 * leniently with kotlinx-serialization JsonElement (their schemas drift), so a
 * field rename never crashes search — it just yields fewer fields.
 */
@Singleton
class MarketplaceApi @Inject constructor(
    private val http: OkHttpClient,
    @Dispatcher(OrionDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(source: ModelSource, query: String): List<RemoteModel> = withContext(io) {
        when (source) {
            ModelSource.HUGGINGFACE -> searchHf(query)
            ModelSource.MODELSCOPE -> searchModelScope(query)
        }
    }

    suspend fun listFiles(source: ModelSource, repoId: String): List<RemoteFile> = withContext(io) {
        when (source) {
            ModelSource.HUGGINGFACE -> hfFiles(repoId)
            ModelSource.MODELSCOPE -> modelScopeFiles(repoId)
        }
    }

    /** Direct, resumable download URL for a single file in a repo. */
    fun fileUrl(source: ModelSource, repoId: String, path: String): String = when (source) {
        ModelSource.HUGGINGFACE -> "https://huggingface.co/$repoId/resolve/main/$path?download=true"
        ModelSource.MODELSCOPE -> "https://modelscope.cn/api/v1/models/$repoId/repo?Revision=master&FilePath=$path"
    }

    // ---- HuggingFace ----

    private fun searchHf(query: String): List<RemoteModel> {
        val q = query.ifBlank { "mnn" }
        val url = "https://huggingface.co/api/models?search=${enc(q)}&limit=40&sort=downloads&direction=-1"
        val body = get(url) ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull
                ?: o["modelId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val tags = (o["tags"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            RemoteModel(
                source = ModelSource.HUGGINGFACE,
                repoId = id,
                org = ModelHeuristics.org(id),
                name = ModelHeuristics.name(id),
                downloads = o["downloads"]?.jsonPrimitive?.longOrNull ?: 0,
                likes = o["likes"]?.jsonPrimitive?.longOrNull ?: 0,
                quant = ModelHeuristics.quant(id),
                capabilities = ModelHeuristics.capabilities(id, tags),
                mnnReady = ModelHeuristics.isMnn(id, tags),
            )
        }
    }

    private fun hfFiles(repoId: String): List<RemoteFile> {
        val url = "https://huggingface.co/api/models/$repoId/tree/main?recursive=true"
        val body = get(url) ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o["type"]?.jsonPrimitive?.contentOrNull != "file") return@mapNotNull null
            val path = o["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            RemoteFile(path, o["size"]?.jsonPrimitive?.longOrNull ?: 0)
        }
    }

    // ---- ModelScope ----

    private fun searchModelScope(query: String): List<RemoteModel> {
        // PUT to the dolphin search endpoint. Best-effort: returns empty on drift.
        val q = query.ifBlank { "MNN" }
        val url = "https://modelscope.cn/api/v1/dolphin/models"
        val payload = """{"PageSize":40,"PageNumber":1,"Name":"$q","SortBy":"Default"}"""
        val body = put(url, payload) ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val models = root["Data"]?.jsonObject?.get("Model")?.jsonObject
            ?.get("Models")?.let { it as? JsonArray } ?: return emptyList()
        return models.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val path = o["Path"]?.jsonPrimitive?.contentOrNull
            val name = o["Name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val repoId = if (!path.isNullOrBlank()) "$path/$name" else name
            val tags = (o["Tags"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            RemoteModel(
                source = ModelSource.MODELSCOPE,
                repoId = repoId,
                org = ModelHeuristics.org(repoId),
                name = ModelHeuristics.name(repoId),
                downloads = o["Downloads"]?.jsonPrimitive?.longOrNull ?: 0,
                likes = o["Stars"]?.jsonPrimitive?.longOrNull ?: 0,
                quant = ModelHeuristics.quant(repoId),
                capabilities = ModelHeuristics.capabilities(repoId, tags),
                mnnReady = ModelHeuristics.isMnn(repoId, tags),
            )
        }
    }

    private fun modelScopeFiles(repoId: String): List<RemoteFile> {
        val url = "https://modelscope.cn/api/v1/models/$repoId/repo/files?Revision=master&Recursive=true"
        val body = get(url) ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val files = root["Data"]?.jsonObject?.get("Files")?.let { it as? JsonArray } ?: return emptyList()
        return files.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o["Type"]?.jsonPrimitive?.contentOrNull == "tree") return@mapNotNull null
            val path = o["Path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            RemoteFile(path, o["Size"]?.jsonPrimitive?.longOrNull ?: 0)
        }
    }

    // ---- HTTP helpers ----

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    private fun put(url: String, jsonBody: String): String? = runCatching {
        val media = "application/json".toMediaType()
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .put(jsonBody.toRequestBody(media))
            .build()
        http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val UA = "OrionBuilderAndroid/1.0"
    }
}
