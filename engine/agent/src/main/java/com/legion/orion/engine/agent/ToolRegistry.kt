package com.legion.orion.engine.agent

import com.legion.orion.engine.providers.ToolSchema

/** Holds the tools available to the agent and exposes their schemas to providers. */
class ToolRegistry(tools: List<AgentTool>) {
    private val byName: Map<String, AgentTool> = tools.associateBy { it.name }

    fun get(name: String): AgentTool? = byName[name]

    fun schemas(): List<ToolSchema> = byName.values.map { it.toSchema() }

    val size: Int get() = byName.size
}
