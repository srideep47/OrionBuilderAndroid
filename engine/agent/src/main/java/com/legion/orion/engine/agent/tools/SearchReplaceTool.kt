package com.legion.orion.engine.agent.tools

import com.legion.orion.engine.agent.AgentTool
import com.legion.orion.engine.agent.ToolContext
import com.legion.orion.engine.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Edits an existing file by replacing a (whitespace-tolerant) block of text. */
class SearchReplaceTool : AgentTool {
    override val name = "search_replace"
    override val description =
        "Replace a block of text in an existing file. 'search' is matched whitespace-tolerantly; " +
            "provide enough surrounding context to make it unique."
    override val parameters = objectSchema(
        Param("path", "string", "Project-relative file path"),
        Param("search", "string", "Existing text block to find"),
        Param("replace", "string", "Replacement text (complete, no placeholders)"),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        fun str(k: String) = args[k]?.jsonPrimitive?.contentOrNull
        val path = str("path") ?: return ToolResult("Missing 'path'", isError = true)
        val search = str("search") ?: return ToolResult("Missing 'search'", isError = true)
        val replace = str("replace") ?: return ToolResult("Missing 'replace'", isError = true)

        PlaceholderGuard.findViolation(replace)?.let {
            return ToolResult("Edit rejected: replacement contains placeholder '$it'.", isError = true)
        }

        val file = PathJail.resolve(ctx.projectDir, path)
        if (!file.isFile) return ToolResult("File not found: $path", isError = true)

        val original = file.readText()
        val updated = SearchReplaceMatcher.replaceFirst(original, search, replace)
            ?: return ToolResult(
                "Search block not found in $path. Re-read the file and copy the exact text to replace.",
                isError = true,
            )
        if (updated == original) return ToolResult("No change (search and replace are identical) in $path")
        file.writeText(updated)
        return ToolResult("Edited $path")
    }
}
