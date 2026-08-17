package com.legion.orion.engine.agent.tools

import com.legion.orion.engine.agent.AgentTool
import com.legion.orion.engine.agent.ToolContext
import com.legion.orion.engine.agent.ToolResult
import java.io.File
import kotlinx.serialization.json.JsonObject

private val SKIP_WALK_DIRS = setOf("node_modules", ".git", ".orion", "build", ".gradle", "dist")

/**
 * Compact project map: lists code files with their top-level symbols (lightweight regex extraction
 * for Kotlin / TS / JS / Python). A tree-sitter-backed version replaces this in a later phase.
 */
class RepoMapTool : AgentTool {
    override val name = "get_repo_map"
    override val description = "Get a compact map of project files and their top-level symbols."
    override val parameters = objectSchema()

    private val symbolRegex = Regex(
        """^\s*(?:export\s+)?(?:public\s+|private\s+|internal\s+|default\s+)?""" +
            """(?:abstract\s+|final\s+|suspend\s+)?""" +
            """(class|interface|object|fun|function|val|const|def)\s+([A-Za-z_][A-Za-z0-9_]*)""",
    )

    private val codeExt = setOf("kt", "kts", "ts", "tsx", "js", "jsx", "py", "java", "vue", "svelte")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val base = ctx.projectDir.canonicalFile
        val sb = StringBuilder()
        var fileCount = 0
        ctx.projectDir.walkTopDown()
            .onEnter { it == ctx.projectDir || it.name !in SKIP_WALK_DIRS }
            .filter { it.isFile && it.extension.lowercase() in codeExt && it.length() < 500_000 }
            .sortedBy { it.path }
            .take(200)
            .forEach { file ->
                val rel = file.canonicalFile.relativeTo(base).path.replace('\\', '/')
                val symbols = ArrayList<String>()
                runCatching {
                    file.useLines { lines ->
                        lines.forEach { line ->
                            symbolRegex.find(line)?.let { m ->
                                symbols.add("${m.groupValues[1]} ${m.groupValues[2]}")
                                if (symbols.size >= 40) return@forEach
                            }
                        }
                    }
                }
                fileCount++
                sb.append(rel)
                if (symbols.isNotEmpty()) sb.append("\n  ").append(symbols.joinToString("\n  "))
                sb.append("\n")
            }
        return if (fileCount == 0) ToolResult("(no code files found)") else ToolResult(sb.toString().trim())
    }
}
