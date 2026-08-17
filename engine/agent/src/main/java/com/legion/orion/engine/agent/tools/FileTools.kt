package com.legion.orion.engine.agent.tools

import com.legion.orion.engine.agent.AgentTool
import com.legion.orion.engine.agent.ToolContext
import com.legion.orion.engine.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private val SKIP_WALK_DIRS = setOf("node_modules", ".git", ".orion", "build", ".gradle", "dist")

/** Reads a UTF-8 text file inside the project. */
class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description = "Read the full contents of a text file in the project."
    override val parameters = objectSchema(Param("path", "string", "Project-relative file path"))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("Missing 'path'", isError = true)
        val file = PathJail.resolve(ctx.projectDir, path)
        if (!file.isFile) return ToolResult("File not found: $path", isError = true)
        val text = file.readText()
        // Number lines lightly to help the model target search_replace edits.
        return ToolResult(text)
    }
}

/** Writes (creates or overwrites) a text file. Rejected if the content contains placeholders. */
class WriteFileTool : AgentTool {
    override val name = "write_file"
    override val description = "Create or overwrite a text file with complete contents (no placeholders)."
    override val parameters = objectSchema(
        Param("path", "string", "Project-relative file path"),
        Param("content", "string", "Full file contents"),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("Missing 'path'", isError = true)
        val content = args.str("content") ?: return ToolResult("Missing 'content'", isError = true)
        PlaceholderGuard.findViolation(content)?.let {
            return ToolResult(
                "Write rejected: contains placeholder '$it'. Write the COMPLETE file contents.",
                isError = true,
            )
        }
        val file = PathJail.resolve(ctx.projectDir, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return ToolResult("Wrote ${content.length} chars to $path")
    }
}

/** Deletes a file. */
class DeleteFileTool : AgentTool {
    override val name = "delete_file"
    override val description = "Delete a file in the project."
    override val parameters = objectSchema(Param("path", "string", "Project-relative file path"))
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("Missing 'path'", isError = true)
        val file = PathJail.resolve(ctx.projectDir, path)
        if (!file.exists()) return ToolResult("Nothing to delete: $path")
        return if (file.deleteRecursively()) ToolResult("Deleted $path")
        else ToolResult("Failed to delete $path", isError = true)
    }
}

/** Lists files under a directory (recursive, project-relative paths). */
class ListFilesTool : AgentTool {
    override val name = "list_files"
    override val description = "List files in the project (optionally under a subdirectory)."
    override val parameters = objectSchema(
        Param("path", "string", "Subdirectory to list (default: project root)", required = false),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val sub = args.str("path").orEmpty()
        val dir = PathJail.resolve(ctx.projectDir, sub)
        if (!dir.exists()) return ToolResult("Not found: $sub", isError = true)
        val base = ctx.projectDir.canonicalFile
        val entries = dir.walkTopDown()
            .onEnter { it == dir || it.name !in SKIP_WALK_DIRS }
            .filter { it.isFile }
            .map { it.canonicalFile.relativeTo(base).path.replace('\\', '/') }
            .sorted()
            .take(500)
            .toList()
        return if (entries.isEmpty()) ToolResult("(no files)")
        else ToolResult(entries.joinToString("\n"))
    }
}
