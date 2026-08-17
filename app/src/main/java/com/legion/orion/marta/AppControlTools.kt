package com.legion.orion.marta

import com.legion.orion.engine.agent.AgentTool
import com.legion.orion.engine.agent.ToolContext
import com.legion.orion.engine.agent.ToolResult
import com.legion.orion.engine.agent.tools.Param
import com.legion.orion.engine.agent.tools.objectSchema
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class AppControlTools @Inject constructor(
    private val app: AppControl,
) {
    fun list(): List<AgentTool> = listOf(
        StatusTool(app),
        SetTool(app),
        NavigateTool(app),
        LoadModelTool(app),
        UnloadModelTool(app),
        ListProjectsTool(app),
        OpenProjectTool(app),
        HandoffTool(app),
    )
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private class StatusTool(private val app: AppControl) : AgentTool {
    override val name = "orion_status"
    override val description = "Live snapshot of Orion: loaded model, RAM, project, dev server, Godot, installed models."
    override val parameters = objectSchema()
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        ToolResult(app.formatStatus(app.status()))
}

private class SetTool(private val app: AppControl) : AgentTool {
    override val name = "orion_set"
    override val description = "Change an Orion setting. Keys: haptics, compaction, compaction_notify, compaction_threshold, keep_recent_turns, max_agent_iterations, theme_dark."
    override val parameters = objectSchema(
        Param("key", "string", "Setting name"),
        Param("value", "string", "New value (true/false or number)"),
    )
    override val mutating = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val key = args.str("key") ?: return ToolResult("Missing key", isError = true)
        val value = args.str("value") ?: return ToolResult("Missing value", isError = true)
        return ToolResult(app.setSetting(key, value))
    }
}

private class NavigateTool(private val app: AppControl) : AgentTool {
    override val name = "orion_navigate"
    override val description = "Open an Orion screen: marta, code, models, hub, settings."
    override val parameters = objectSchema(Param("screen", "string", "marta | code | models | hub | settings"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val screen = args.str("screen") ?: return ToolResult("Missing screen", isError = true)
        return ToolResult(app.navigate(screen))
    }
}

private class LoadModelTool(private val app: AppControl) : AgentTool {
    override val name = "orion_load_model"
    override val description = "Load a local MNN model. Omit name to load Marta's 2B. backend is cpu (preferred), opencl, vulkan, or qnn."
    override val parameters = objectSchema(
        Param("name", "string", "Model folder or repo name; omit for Marta 2B", required = false),
        Param("backend", "string", "cpu | opencl | vulkan | qnn", required = false),
        Param("voice", "boolean", "Use the short-reply voice profile", required = false),
    )
    override val mutating = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args.str("name")
        val backend = args.str("backend")
        val voice = args["voice"]?.jsonPrimitive?.booleanOrNull == true
        val msg = if (name.isNullOrBlank() || name.contains("marta", true) || name.contains("2B", true)) {
            app.loadMartaModel(voice)
        } else {
            app.loadNamedModel(name, backend)
        }
        return ToolResult(msg, isError = msg.startsWith("No ") || msg.contains("not on this phone"))
    }
}

private class UnloadModelTool(private val app: AppControl) : AgentTool {
    override val name = "orion_unload_model"
    override val description = "Unload the current local model and free RAM."
    override val parameters = objectSchema()
    override val mutating = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = ToolResult(app.unloadModel())
}

private class ListProjectsTool(private val app: AppControl) : AgentTool {
    override val name = "orion_list_projects"
    override val description = "List Orion projects on this phone."
    override val parameters = objectSchema()
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = ToolResult(app.listProjects())
}

private class OpenProjectTool(private val app: AppControl) : AgentTool {
    override val name = "orion_open_project"
    override val description = "Switch the active project and open the Code screen."
    override val parameters = objectSchema(Param("name", "string", "Project name or id"))
    override val mutating = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args.str("name") ?: return ToolResult("Missing name", isError = true)
        return ToolResult(app.openProject(name))
    }
}

private class HandoffTool(private val app: AppControl) : AgentTool {
    override val name = "handoff_to_brain"
    override val description =
        "Give a bigger brain a job: claude (Claude Code), grok (Grok Build), codex, or build (on-device Orion agent). " +
            "Use this for software, long plans, or anything that needs more intelligence than Marta."
    override val parameters = objectSchema(
        Param("brain", "string", "claude | grok | codex | build"),
        Param("brief", "string", "What the second brain should do. Be specific."),
    )
    override val mutating = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val brain = args.str("brain") ?: return ToolResult("Missing brain", isError = true)
        val brief = args.str("brief") ?: return ToolResult("Missing brief", isError = true)
        return ToolResult(app.handoffToBrain(brain, brief))
    }
}
