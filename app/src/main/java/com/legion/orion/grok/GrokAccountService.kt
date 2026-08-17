package com.legion.orion.grok

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Loads the live Grok Build model catalog, account profile, and subscription tier using the same
 * OAuth token already stored by `grok login` in `~/.grok/auth.json`.
 *
 * Models / efforts / context windows come from `GET /v1/models` (mirrored in `models_cache.json`).
 * Account label + tier come from `/v1/user` and `/v1/settings`. Context usage for the open session
 * is read from Grok's own `signals.json` when a session directory exists.
 */
class GrokAccountService(private val toolchain: GrokToolchain) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class EffortOption(
        val id: String,
        val label: String,
        val description: String = "",
        val isDefault: Boolean = false,
    )

    data class ModelOption(
        val id: String,
        val name: String,
        val description: String = "",
        val contextWindow: Long = 500_000,
        val defaultEffort: String = "high",
        val efforts: List<EffortOption> = DEFAULT_EFFORTS,
        val supportsEffort: Boolean = true,
    )

    data class Snapshot(
        val loggedIn: Boolean,
        val email: String? = null,
        val displayName: String? = null,
        val subscriptionTier: String? = null,
        val hasGrokCodeAccess: Boolean = false,
        val models: List<ModelOption> = emptyList(),
        val defaultModelId: String? = null,
        /** Best-effort live context from the newest local session signals.json. */
        val contextTokensUsed: Long = 0,
        val contextWindowTokens: Long = 0,
        val contextWindowUsagePercent: Double? = null,
        val primaryModelId: String? = null,
        /** Billing-period usage from `GET /v1/billing` (what Grok Build enforces). */
        val usageLimit: Long = 0,
        val usageUsed: Long = 0,
        val usagePeriodStart: String? = null,
        val usagePeriodEnd: String? = null,
        val error: String? = null,
        val fetchedAtMillis: Long = System.currentTimeMillis(),
    ) {
        val usagePercent: Double?
            get() = if (usageLimit > 0) (usageUsed * 100.0 / usageLimit).coerceIn(0.0, 100.0) else null
    }

    suspend fun fetch(includeNetwork: Boolean = true): Snapshot = withContext(Dispatchers.IO) {
        val token = readAccessToken()
        val fromCache = parseModelsCache()
        if (token.isNullOrBlank()) {
            return@withContext Snapshot(
                loggedIn = false,
                models = fromCache.ifEmpty { listOf(FALLBACK_MODEL) },
                defaultModelId = fromCache.firstOrNull()?.id ?: FALLBACK_MODEL.id,
                error = "Not signed in",
            )
        }

        var models = fromCache
        var email: String? = null
        var displayName: String? = null
        var tier: String? = null
        var hasAccess = false
        var defaultModel: String? = null
        var usageLimit = 0L
        var usageUsed = 0L
        var periodStart: String? = null
        var periodEnd: String? = null
        var error: String? = null

        if (includeNetwork) {
            runCatching {
                val body = getJson("https://cli-chat-proxy.grok.com/v1/models", token)
                parseModelsResponse(body).takeIf { it.isNotEmpty() }?.let { models = it }
            }.onFailure { error = it.message?.take(200) }

            runCatching {
                val user = getJson("https://cli-chat-proxy.grok.com/v1/user", token)
                email = user.string("email")
                displayName = listOfNotNull(user.string("firstName"), user.string("lastName"))
                    .joinToString(" ").trim().ifBlank { null }
                hasAccess = user.bool("hasGrokCodeAccess") == true
            }.onFailure { if (error == null) error = it.message?.take(200) }

            runCatching {
                val settings = getJson("https://cli-chat-proxy.grok.com/v1/settings", token)
                tier = settings.string("subscription_tier_display")
                    ?: settings.string("subscriptionTier")
                defaultModel = settings.string("default_model")
                    ?: settings.obj("default_model")?.string("model")
                    ?: settings.obj("default_model")?.string("id")
            }

            runCatching {
                val billing = getJson("https://cli-chat-proxy.grok.com/v1/billing", token)
                val config = billing.obj("config")
                usageLimit = config?.obj("monthlyLimit")?.long("val") ?: 0L
                usageUsed = config?.obj("used")?.long("val") ?: 0L
                periodStart = config?.string("billingPeriodStart")
                periodEnd = config?.string("billingPeriodEnd")
            }.onFailure { if (error == null) error = it.message?.take(200) }
        }

        // Drop fake/legacy ids (e.g. old "grok-build" chip) that are not in the live catalog.
        models = models.filter { it.id.isNotBlank() && !it.id.equals("grok-build", ignoreCase = true) }
            .ifEmpty { listOf(FALLBACK_MODEL) }

        val context = readLatestSessionContext()
        val resolvedDefault = listOfNotNull(defaultModel, context.modelId, FALLBACK_MODEL.id)
            .firstOrNull { id -> models.any { it.id == id } }
            ?: models.first().id

        Snapshot(
            loggedIn = true,
            email = email,
            displayName = displayName,
            subscriptionTier = tier,
            hasGrokCodeAccess = hasAccess,
            models = models,
            defaultModelId = resolvedDefault,
            contextTokensUsed = context.used,
            contextWindowTokens = context.window,
            contextWindowUsagePercent = context.percent,
            primaryModelId = context.modelId,
            usageLimit = usageLimit,
            usageUsed = usageUsed,
            usagePeriodStart = periodStart,
            usagePeriodEnd = periodEnd,
            error = error,
        )
    }

    /** Cheap local-only refresh of context from the newest signals.json (no network). */
    fun readLatestSessionContext(): SessionContext {
        val sessionsRoot = File(toolchain.grokHomeOnHost, "sessions")
        if (!sessionsRoot.isDirectory) return SessionContext()
        val signals = sessionsRoot.walkTopDown()
            .filter { it.isFile && it.name == "signals.json" }
            .maxByOrNull { it.lastModified() }
            ?: return SessionContext()
        return runCatching {
            val root = json.parseToJsonElement(signals.readText()).jsonObject
            SessionContext(
                used = root.long("contextTokensUsed") ?: 0L,
                window = root.long("contextWindowTokens") ?: 0L,
                percent = root.double("contextWindowUsage")
                    ?: root.long("contextTokensUsed")?.let { used ->
                        val win = root.long("contextWindowTokens") ?: return@let null
                        if (win > 0) used * 100.0 / win else null
                    },
                modelId = root.string("primaryModelId"),
            )
        }.getOrDefault(SessionContext())
    }

    data class SessionContext(
        val used: Long = 0,
        val window: Long = 0,
        val percent: Double? = null,
        val modelId: String? = null,
    )

    private fun readAccessToken(): String? {
        val file = File(toolchain.grokHomeOnHost, "auth.json")
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        if (text.isBlank() || text.trim() == "{}") return null
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return null
        root.values.forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            listOf("key", "accessToken", "access_token", "token").forEach { k ->
                obj.string(k)?.takeIf { it.length > 20 }?.let { return it }
            }
        }
        return null
    }

    private fun parseModelsCache(): List<ModelOption> {
        val file = File(toolchain.grokHomeOnHost, "models_cache.json")
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val models = root["models"] as? JsonObject ?: return@runCatching emptyList()
            models.entries.mapNotNull { (id, value) ->
                val info = (value as? JsonObject)?.obj("info") ?: value as? JsonObject
                info?.let { parseModelInfo(id, it) }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseModelsResponse(root: JsonObject): List<ModelOption> {
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id") ?: obj.string("model") ?: return@mapNotNull null
            parseModelInfo(id, obj)
        }
    }

    private fun parseModelInfo(id: String, info: JsonObject): ModelOption {
        val efforts = (info["reasoning_efforts"] as? JsonArray)
            ?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.string("value") ?: o.string("id") ?: return@mapNotNull null
                // UI chips are just High / Medium / Low — strip "Effort" suffix from API labels.
                val rawLabel = o.string("label") ?: id.replaceFirstChar(Char::uppercase)
                EffortOption(
                    id = id,
                    label = rawLabel.removeSuffix(" Effort").trim().ifBlank { id.replaceFirstChar(Char::uppercase) },
                    description = o.string("description").orEmpty(),
                    isDefault = o.bool("default") == true,
                )
            }
            ?.ifEmpty { null }
            ?: DEFAULT_EFFORTS
        val defaultEffort = efforts.firstOrNull { it.isDefault }?.id
            ?: info.string("reasoning_effort")
            ?: "high"
        return ModelOption(
            id = id,
            name = info.string("name") ?: id,
            description = info.string("description").orEmpty(),
            contextWindow = info.long("context_window") ?: 500_000L,
            defaultEffort = defaultEffort,
            efforts = efforts,
            supportsEffort = info.bool("supports_reasoning_effort") != false,
        )
    }

    private fun getJson(url: String, token: String): JsonObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", "GrokBuild/0.2.118 Orion")
            .get()
            .build()
        http.newCall(req).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "HTTP ${response.code} from $url: ${body.take(180)}" }
            return json.parseToJsonElement(body).jsonObject
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull
            ?: (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull
            ?: (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    companion object {
        val DEFAULT_EFFORTS = listOf(
            EffortOption("high", "High", "Highest implementation quality with extensive reasoning", isDefault = true),
            EffortOption("medium", "Medium", "Balanced effort with standard implementation and testing"),
            EffortOption("low", "Low", "Quick, fast implementations"),
        )

        val FALLBACK_MODEL = ModelOption(
            id = "grok-4.5",
            name = "Grok 4.5",
            description = "xAI frontier model for Grok Build",
            contextWindow = 500_000,
            defaultEffort = "high",
            efforts = DEFAULT_EFFORTS,
        )
    }
}
