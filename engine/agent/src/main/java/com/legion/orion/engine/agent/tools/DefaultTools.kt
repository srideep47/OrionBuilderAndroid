package com.legion.orion.engine.agent.tools

import com.legion.orion.engine.agent.AgentTool
import com.legion.orion.engine.agent.ToolRegistry

/** Assembles the Phase-3 tool set. Terminal/dev-server/build tools are added in later phases. */
object DefaultTools {
    fun list(): List<AgentTool> = listOf(
        ReadFileTool(),
        WriteFileTool(),
        DeleteFileTool(),
        ListFilesTool(),
        SearchReplaceTool(),
        GrepTool(),
        RepoMapTool(),
    )

    fun registry(): ToolRegistry = ToolRegistry(list())
}
