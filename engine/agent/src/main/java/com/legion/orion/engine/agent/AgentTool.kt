package com.legion.orion.engine.agent

import com.legion.orion.engine.providers.ToolSchema
import java.io.File
import kotlinx.serialization.json.JsonObject

/** Result of a tool invocation, fed back to the model as a tool message. */
data class ToolResult(
    val content: String,
    val isError: Boolean = false,
)

/** Execution context shared by all tools for one agent run. */
class ToolContext(
    /** Jail root — tools must not read/write outside this directory. */
    val projectDir: File,
)

/**
 * A capability the agent can invoke. [parameters] is a JSON Schema object. Tools that change
 * files set [mutating] = true so the loop snapshots before running them.
 */
interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonObject
    val mutating: Boolean get() = false

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult

    fun toSchema(): ToolSchema = ToolSchema(name, description, parameters)
}
