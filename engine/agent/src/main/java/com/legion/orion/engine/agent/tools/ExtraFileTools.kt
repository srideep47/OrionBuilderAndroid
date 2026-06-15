package com.legion.orion.engine.agent.tools

import com.legion.orion.engine.agent.AgentTool
import com.legion.orion.engine.agent.ToolContext
import com.legion.orion.engine.agent.ToolResult
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** Reads several files at once — fewer round-trips than read_file per file. */
class ReadManyFilesTool : AgentTool {
    override val name = "read_many_files"
    override val description =
        "Read multiple project files in one call. Returns each path followed by its contents. " +
            "Use when you need to see several files together (e.g. index.html + style.css + main.js)."
    override val parameters = objectSchema(
        Param("paths", "string", "Project-relative file paths, separated by commas or newlines"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val paths = args.string("paths").orEmpty()
            .split(Regex("[,\\n]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paths.isEmpty()) return ToolResult("Missing 'paths' (comma/newline-separated file paths)", isError = true)
        val sb = StringBuilder()
        var anyError = false
        for (p in paths.take(20)) {
            val f = PathJail.resolve(ctx.projectDir, p)
            sb.append("===== ").append(p).append(" =====\n")
            if (f.isFile) sb.append(f.readText().take(20_000)).append("\n\n")
            else { sb.append("(not found)\n\n"); anyError = true }
        }
        return ToolResult(sb.toString().trim(), isError = anyError)
    }
}

/** Appends text to a file (creates it if missing). */
class AppendFileTool : AgentTool {
    override val name = "append_file"
    override val description = "Append text to the end of a project file (creates the file if it doesn't exist)."
    override val parameters = objectSchema(
        Param("path", "string", "Project-relative file path"),
        Param("content", "string", "Text to append"),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.string("path") ?: return ToolResult("Missing 'path'", isError = true)
        val content = args.string("content") ?: return ToolResult("Missing 'content'", isError = true)
        PlaceholderGuard.findViolation(content)?.let {
            return ToolResult("Append rejected: contains placeholder '$it'. Write complete content.", isError = true)
        }
        val file = PathJail.resolve(ctx.projectDir, path)
        file.parentFile?.mkdirs()
        file.appendText(content)
        return ToolResult("Appended ${content.length} chars to $path")
    }
}

/** Creates a directory (and parents). */
class MakeDirectoryTool : AgentTool {
    override val name = "make_directory"
    override val description = "Create a directory (and any missing parents) in the project."
    override val parameters = objectSchema(Param("path", "string", "Project-relative directory path"))
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.string("path") ?: return ToolResult("Missing 'path'", isError = true)
        val dir = PathJail.resolve(ctx.projectDir, path)
        return if (dir.mkdirs() || dir.isDirectory) ToolResult("Created directory $path")
        else ToolResult("Failed to create $path", isError = true)
    }
}

/** Moves or renames a file/directory within the project. */
class MoveFileTool : AgentTool {
    override val name = "move_file"
    override val description = "Move or rename a file or directory within the project."
    override val parameters = objectSchema(
        Param("from", "string", "Existing project-relative path"),
        Param("to", "string", "New project-relative path"),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val from = args.string("from") ?: return ToolResult("Missing 'from'", isError = true)
        val to = args.string("to") ?: return ToolResult("Missing 'to'", isError = true)
        val src = PathJail.resolve(ctx.projectDir, from)
        val dst = PathJail.resolve(ctx.projectDir, to)
        if (!src.exists()) return ToolResult("Source not found: $from", isError = true)
        dst.parentFile?.mkdirs()
        return if (src.renameTo(dst)) ToolResult("Moved $from → $to")
        else {
            // Cross-dir rename can fail; fall back to copy+delete.
            runCatching {
                src.copyRecursively(dst, overwrite = true)
                src.deleteRecursively()
            }.fold(
                onSuccess = { ToolResult("Moved $from → $to") },
                onFailure = { ToolResult("Failed to move $from → $to: ${it.message}", isError = true) },
            )
        }
    }
}
