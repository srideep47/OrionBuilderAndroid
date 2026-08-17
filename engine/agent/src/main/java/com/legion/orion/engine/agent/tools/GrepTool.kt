package com.legion.orion.engine.agent.tools

import com.legion.orion.engine.agent.AgentTool
import com.legion.orion.engine.agent.ToolContext
import com.legion.orion.engine.agent.ToolResult
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val SKIP_WALK_DIRS = setOf("node_modules", ".git", ".orion", "build", ".gradle", "dist")

/**
 * Regex content search across project files. Kotlin implementation for now; a bundled-ripgrep
 * fast path arrives with the build-tools work in a later phase.
 */
class GrepTool : AgentTool {
    override val name = "grep"
    override val description = "Search file contents by regular expression. Returns file:line: matches."
    override val parameters = objectSchema(
        Param("pattern", "string", "Regular expression to search for"),
        Param("path", "string", "Subdirectory to search (default: project root)", required = false),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        fun str(k: String) = args[k]?.jsonPrimitive?.contentOrNull
        val patternStr = str("pattern") ?: return ToolResult("Missing 'pattern'", isError = true)
        val regex = runCatching { Regex(patternStr) }.getOrElse {
            return ToolResult("Invalid regex: ${it.message}", isError = true)
        }
        val dir = PathJail.resolve(ctx.projectDir, str("path").orEmpty())
        val base = ctx.projectDir.canonicalFile
        val hits = ArrayList<String>()
        dir.walkTopDown()
            .onEnter { it == dir || it.name !in SKIP_WALK_DIRS }
            .filter { it.isFile && it.length() < 1_000_000 }
            .forEach { file ->
                runCatching {
                    file.useLines { lines ->
                        lines.forEachIndexed { idx, line ->
                            if (regex.containsMatchIn(line)) {
                                val rel = file.canonicalFile.relativeTo(base).path.replace('\\', '/')
                                hits.add("$rel:${idx + 1}: ${line.trim().take(200)}")
                                if (hits.size >= 200) return@forEach
                            }
                        }
                    }
                }
            }
        return if (hits.isEmpty()) ToolResult("No matches for /$patternStr/")
        else ToolResult(hits.take(200).joinToString("\n"))
    }
}
