package com.legion.orion.internet

import com.legion.orion.engine.agent.AgentTool
import com.legion.orion.engine.agent.ToolContext
import com.legion.orion.engine.agent.ToolResult
import com.legion.orion.engine.agent.tools.Param
import com.legion.orion.engine.agent.tools.objectSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Internet tools for the Kotlin Chat/BUILD agent loop. Same capabilities are exposed to
 * Claude Code / Codex / Grok via [com.legion.orion.claudecode.ClaudeToolBridge] + `orion-tool`.
 */
object InternetAgentTools {
    fun list(
        internet: InternetManager,
        identities: DevIdentityStore,
    ): List<AgentTool> = listOf(
        WebSearchTool(internet),
        WebFetchTool(internet),
        WebDownloadTool(internet),
        ListDevIdentitiesTool(identities),
    )
}

private class WebSearchTool(private val internet: InternetManager) : AgentTool {
    override val name = "web_search"
    override val description =
        "Search the public internet for current information, docs, libraries, free assets, APIs, " +
            "or error solutions. Returns titled results with URLs and snippets. Use web_fetch next " +
            "to read a promising page in full."
    override val parameters = objectSchema(
        Param("query", "string", "Search query"),
        Param("max_results", "integer", "Max hits (1-15, default 8)", required = false),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args.str("query") ?: return ToolResult("Missing query", isError = true)
        val max = args["max_results"]?.jsonPrimitive?.intOrNull ?: 8
        return internet.search(query, max).fold(
            onSuccess = { ToolResult(internet.formatSearch(it)) },
            onFailure = { ToolResult(it.message ?: "Search failed", isError = true) },
        )
    }
}

private class WebFetchTool(private val internet: InternetManager) : AgentTool {
    override val name = "web_fetch"
    override val description =
        "Fetch a public http(s) URL and return its content for research. HTML pages are converted " +
            "to readable text by default (mode=text). Use mode=html for raw markup, mode=raw for " +
            "unprocessed body. Prefer web_download for binary assets (images, zips, fonts)."
    override val parameters = objectSchema(
        Param("url", "string", "http(s) URL to fetch"),
        Param("mode", "string", "text | html | raw (default text)", required = false),
        Param("max_chars", "integer", "Max characters returned (default 24000)", required = false),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = args.str("url") ?: return ToolResult("Missing url", isError = true)
        val mode = args.str("mode") ?: "text"
        val maxChars = args["max_chars"]?.jsonPrimitive?.intOrNull
            ?: InternetManager.DEFAULT_FETCH_CHARS
        return internet.fetch(url, mode, maxChars).fold(
            onSuccess = { ToolResult(internet.formatFetch(it)) },
            onFailure = { ToolResult(it.message ?: "Fetch failed", isError = true) },
        )
    }
}

private class WebDownloadTool(private val internet: InternetManager) : AgentTool {
    override val name = "web_download"
    override val description =
        "Download a remote asset into the current project in realtime (images, CSS/JS, zips, " +
            "fonts, free 3D models, sound packs, docs PDFs, etc.). Path is project-relative and " +
            "jailed. Default max 50MB (hard cap 200MB)."
    override val parameters = objectSchema(
        Param("url", "string", "http(s) URL of the asset"),
        Param("path", "string", "Project-relative destination path including filename"),
        Param("max_bytes", "integer", "Max download size in bytes (default 52428800)", required = false),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = args.str("url") ?: return ToolResult("Missing url", isError = true)
        val path = args.str("path") ?: return ToolResult("Missing path", isError = true)
        val maxBytes = args["max_bytes"]?.jsonPrimitive?.longOrNull
            ?: InternetManager.DEFAULT_DOWNLOAD_BYTES
        return internet.download(url, ctx.projectDir, path, maxBytes).fold(
            onSuccess = { ToolResult(internet.formatDownload(it)) },
            onFailure = { ToolResult(it.message ?: "Download failed", isError = true) },
        )
    }
}

private class ListDevIdentitiesTool(private val identities: DevIdentityStore) : AgentTool {
    override val name = "list_dev_identities"
    override val description =
        "List developer emails/identities the user has stored for Internet Manager account assist. " +
            "Returns labels and emails only — never passwords. Full automated signup/login is Phase 2; " +
            "use this to know which identity the user expects for a service."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        ToolResult(identities.formatPublicList(identities.list()))
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
