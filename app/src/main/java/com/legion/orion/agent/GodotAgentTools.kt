package com.legion.orion.agent

import com.legion.orion.engine.agent.AgentTool
import com.legion.orion.engine.agent.ToolContext
import com.legion.orion.engine.agent.ToolResult
import com.legion.orion.engine.agent.tools.Param
import com.legion.orion.engine.agent.tools.objectSchema
import com.legion.orion.runtime.GodotController
import com.legion.orion.runtime.GodotProcessController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Agent tools giving the local/BUILD-mode agent (and, via the mirrored `ClaudeToolBridge` branches,
 * the embedded Claude Code CLI — a fully separate wiring path with no shared code, see that file)
 * full access to the embedded Godot engine: launch/stop the isolated `:godot` process, inspect and
 * mutate the live running scene tree, and drive it with simulated input — the "AI should have full
 * access to and be fully aware of what's possible in the engine" requirement.
 *
 * Every mutating op below goes through [GodotController.call], which returns the bridge's *raw* JSON
 * response verbatim as the tool result text (not a curated summary) — the model sees exactly what
 * `orion_bridge_server_ops.cpp` returned, including `error` messages on failure, so it can reason
 * about *why* something didn't work rather than just that it didn't.
 *
 * Node paths use Godot's own NodePath syntax rooted at the viewport (e.g. `/root/Main/Player`) —
 * `godot_scene_tree` is the way to discover what's actually there before calling anything else.
 */
@Singleton
class GodotAgentTools @Inject constructor(
    private val godot: GodotController,
    private val process: GodotProcessController,
) {
    fun list(): List<AgentTool> = listOf(
        GodotLaunchTool(process),
        GodotStopTool(process),
        GodotStatusTool(godot, process),
        GodotSceneTreeTool(godot),
        GodotScreenshotTool(godot),
        GodotGetPropertyTool(godot),
        GodotSetPropertyTool(godot),
        GodotCallMethodTool(godot),
        GodotListPropertiesTool(godot),
        GodotListMethodsTool(godot),
        GodotCreateNodeTool(godot),
        GodotDeleteNodeTool(godot),
        GodotSetPausedTool(godot),
        GodotStepTool(godot),
        GodotSimulateInputTool(godot),
        GodotReloadScriptTool(godot),
        GodotCapabilitiesTool(godot),
        GodotClassDbTool(godot),
        GodotSaveSceneTool(godot),
        GodotChangeSceneTool(godot),
        GodotProjectSettingTool(godot),
        GodotInputActionsTool(godot),
    )
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/**
 * Surfaces the bridge's raw response as the tool result, verbatim — the LLM reads the same
 * `ok`/`error`/data shape the C++ side produced, rather than a Kotlin-side reinterpretation of it.
 */
private fun bridgeResult(response: JsonObject?): ToolResult {
    if (response == null) {
        return ToolResult(
            "Godot bridge unreachable (connection failed or timed out) — is the engine running? Call godot_launch first.",
            isError = true,
        )
    }
    val ok = response["ok"]?.jsonPrimitive?.booleanOrNull == true
    return ToolResult(response.toString(), isError = !ok)
}

private class GodotLaunchTool(private val process: GodotProcessController) : AgentTool {
    override val name = "godot_launch"
    override val description =
        "Start the embedded Godot engine for the active Orion project and show its chat panel. " +
            "If needed, installs a starter Godot scene and Orion's control bridge without replacing " +
            "existing project files. Required before any other godot_* tool " +
            "will work. Real process boot — takes a moment; check godot_status to confirm it's ready."
    override val parameters = objectSchema()
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        // showFullscreen=false: this is the AI-tool path — starting the engine here shouldn't yank
        // the user away from chat to a full-screen view. The chat-embedded GodotChatCard picks up
        // rendering on its own once the engine is running.
        val started = process.startForProject(ctx.projectDir, fullscreen = false)
        return if (started) ToolResult("Godot engine started.")
        else ToolResult("Godot engine failed to start (see logcat tag GodotProcessController).", isError = true)
    }
}

private class GodotStopTool(private val process: GodotProcessController) : AgentTool {
    override val name = "godot_stop"
    override val description =
        "Hard-kill the :godot process (real teardown, not pause) — call when done with a game-dev " +
            "session so the engine costs zero memory/CPU while idle."
    override val parameters = objectSchema()
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val stopped = process.teardown()
        return if (stopped) ToolResult("Godot engine stopped.")
        else ToolResult("Godot engine did not confirm teardown in time.", isError = true)
    }
}

private class GodotStatusTool(
    private val godot: GodotController,
    private val process: GodotProcessController,
) : AgentTool {
    override val name = "godot_status"
    override val description = "Check whether the Godot engine is running and its AI-control bridge is responding."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!process.isProcessAlive()) return ToolResult("Godot engine is not running. Call godot_launch first.")
        val pong = godot.ping()
        return if (pong) ToolResult("Godot engine is running; bridge responding normally.")
        else ToolResult("Godot process is alive but the bridge did not respond (still booting, or the hosting UI is backgrounded — see G1/G2 notes).", isError = true)
    }
}

private class GodotSceneTreeTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_scene_tree"
    override val description =
        "Get the full live scene tree (every node's name, class, and children, rooted at /root) of " +
            "the running Godot project. Call this first to discover valid node paths for every other " +
            "godot_* tool that takes a 'path'."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val tree = godot.sceneTree() ?: return ToolResult("Could not read scene tree — is godot_launch running?", isError = true)
        return ToolResult(tree.toString())
    }
}

private class GodotScreenshotTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_screenshot"
    override val description = "Capture a PNG screenshot of the running Godot viewport, saved into the project's .orion/ dir."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        // The bridge always saves to a fixed app-internal path (the running Godot project is this
        // module's own bundled default project, not necessarily ctx.projectDir) — copy it alongside
        // this app's other screenshot tools (TakeScreenshotTool/TakeDeviceScreenshotTool) for a
        // consistent project-relative convention.
        val bridgePath = godot.screenshot() ?: return ToolResult("Screenshot failed — is godot_launch running?", isError = true)
        val out = java.io.File(ctx.projectDir, ".orion/godot.png")
        return runCatching {
            out.parentFile?.mkdirs()
            java.io.File(bridgePath).copyTo(out, overwrite = true)
            ToolResult("Screenshot saved to .orion/godot.png (${out.length()} bytes)")
        }.getOrElse { ToolResult("Failed to copy screenshot: ${it.message}", isError = true) }
    }
}

private class GodotGetPropertyTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_get_property"
    override val description = "Read one property's current value off a live node (e.g. path=/root/Main/Box, property=rotation)."
    override val parameters = objectSchema(
        Param("path", "string", "NodePath rooted at /root, from godot_scene_tree"),
        Param("property", "string", "Property name, e.g. 'position', 'visible', 'rotation'"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("Missing 'path'", isError = true)
        val property = args.str("property") ?: return ToolResult("Missing 'property'", isError = true)
        return bridgeResult(
            godot.call(
                "get_property",
                buildJsonObject {
                    put("path", path)
                    put("property", property)
                },
            ),
        )
    }
}

private class GodotSetPropertyTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_set_property"
    override val description =
        "Set one property on a live node — e.g. move it (path=/root/Main/Box, property=position, " +
            "value=[1,2,3]), hide it (property=visible, value=false), or tweak any exported/script var."
    override val parameters = objectSchema(
        Param("path", "string", "NodePath rooted at /root, from godot_scene_tree"),
        Param("property", "string", "Property name to set"),
        Param("value", "string", "New value — pass its natural JSON type (number/string/bool/array), not necessarily a string"),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("Missing 'path'", isError = true)
        val property = args.str("property") ?: return ToolResult("Missing 'property'", isError = true)
        val value = args["value"] ?: return ToolResult("Missing 'value'", isError = true)
        return bridgeResult(
            godot.call(
                "set_property",
                buildJsonObject {
                    put("path", path)
                    put("property", property)
                    put("value", value)
                },
            ),
        )
    }
}

private class GodotCallMethodTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_call_method"
    override val description =
        "Call any method on a live node — e.g. path=/root/Main/Player, method=take_damage, args=[10]. " +
            "Use godot_list_methods first to see what a node supports."
    override val parameters = objectSchema(
        Param("path", "string", "NodePath rooted at /root, from godot_scene_tree"),
        Param("method", "string", "Method name to call"),
        Param("args", "string", "JSON array of positional arguments, e.g. [10, \"hi\"] (omit for no-arg methods)", required = false),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("Missing 'path'", isError = true)
        val method = args.str("method") ?: return ToolResult("Missing 'method'", isError = true)
        val methodArgs = args["args"] as? JsonArray ?: JsonArray(emptyList())
        return bridgeResult(
            godot.call(
                "call_method",
                buildJsonObject {
                    put("path", path)
                    put("method", method)
                    put("args", methodArgs)
                },
            ),
        )
    }
}

private class GodotListPropertiesTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_list_properties"
    override val description =
        "List every property a live node exposes (name, type, current value, usage flags) — use " +
            "before godot_set_property to discover what's actually settable on that node/class."
    override val parameters = objectSchema(Param("path", "string", "NodePath rooted at /root, from godot_scene_tree"))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("Missing 'path'", isError = true)
        return bridgeResult(godot.call("list_properties", buildJsonObject { put("path", path) }))
    }
}

private class GodotListMethodsTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_list_methods"
    override val description = "List every method a live node exposes (name, argument list, return type) — use before godot_call_method."
    override val parameters = objectSchema(Param("path", "string", "NodePath rooted at /root, from godot_scene_tree"))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("Missing 'path'", isError = true)
        return bridgeResult(godot.call("list_methods", buildJsonObject { put("path", path) }))
    }
}

private class GodotCreateNodeTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_create_node"
    override val description =
        "Create and add a new node of any built-in Godot class (e.g. Sprite2D, RigidBody3D, " +
            "AudioStreamPlayer) as a child of an existing node. For custom scripted behavior, create a " +
            "plain Node then use godot_reload_script to attach a .gd script written with the normal " +
            "file tools. Returns the new node's path."
    override val parameters = objectSchema(
        Param("parent_path", "string", "NodePath of the parent to add under, from godot_scene_tree"),
        Param("class_name", "string", "Built-in Godot class name, e.g. 'Node3D', 'Sprite2D', 'Area3D'"),
        Param("name", "string", "Name for the new node (optional, engine auto-names if omitted)", required = false),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val parentPath = args.str("parent_path") ?: return ToolResult("Missing 'parent_path'", isError = true)
        val className = args.str("class_name") ?: return ToolResult("Missing 'class_name'", isError = true)
        return bridgeResult(
            godot.call(
                "create_node",
                buildJsonObject {
                    put("parent_path", parentPath)
                    put("class_name", className)
                    args.str("name")?.let { put("name", it) }
                },
            ),
        )
    }
}

private class GodotDeleteNodeTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_delete_node"
    override val description = "Remove a node (and its children) from the live running scene. Cannot delete the scene root."
    override val parameters = objectSchema(Param("path", "string", "NodePath rooted at /root, from godot_scene_tree"))
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("Missing 'path'", isError = true)
        return bridgeResult(godot.call("delete_node", buildJsonObject { put("path", path) }))
    }
}

private class GodotSetPausedTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_set_paused"
    override val description = "Pause or resume the running game (SceneTree.paused). Pause before using godot_step to advance frame-by-frame."
    override val parameters = objectSchema(Param("paused", "boolean", "true to pause, false to resume"))
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val paused = args["paused"]?.jsonPrimitive?.booleanOrNull ?: return ToolResult("Missing 'paused'", isError = true)
        return bridgeResult(godot.call("set_paused", buildJsonObject { put("paused", paused) }))
    }
}

private class GodotStepTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_step"
    override val description =
        "Advance the paused game by exactly N frames, then re-pause (there's no true single-step, " +
            "this unpauses for N frames and re-pauses). Requires godot_set_paused(true) first."
    override val parameters = objectSchema(Param("frames", "number", "Number of frames to advance (default 1)", required = false))
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val frames = args["frames"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
        return bridgeResult(godot.call("step", buildJsonObject { put("frames", frames) }))
    }
}

private class GodotSimulateInputTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_simulate_input"
    override val description =
        "Inject simulated input into the running game, as if a player pressed it. type='key': " +
            "keycode (Godot Key enum int), pressed. type='mouse_button': button_index (1=left,2=right," +
            "3=middle), pressed, position=[x,y]. type='mouse_motion': position=[x,y], relative=[x,y]. " +
            "type='action': action (InputMap action name), pressed, strength (0-1, default 1)."
    override val parameters = objectSchema(
        Param("type", "string", "key | mouse_button | mouse_motion | action"),
        Param("keycode", "number", "For type=key: Godot Key enum integer", required = false),
        Param("button_index", "number", "For type=mouse_button: 1=left, 2=right, 3=middle", required = false),
        Param("action", "string", "For type=action: the InputMap action name", required = false),
        Param("pressed", "boolean", "true=press/down, false=release/up (default true)", required = false),
        Param("position", "string", "For mouse_button/mouse_motion: [x, y]", required = false),
        Param("relative", "string", "For mouse_motion: [dx, dy]", required = false),
        Param("strength", "number", "For type=action: analog strength 0-1 (default 1)", required = false),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val type = args.str("type") ?: return ToolResult("Missing 'type'", isError = true)
        val body = buildJsonObject {
            put("type", type)
            args["keycode"]?.let { put("keycode", it) }
            args["button_index"]?.let { put("button_index", it) }
            args.str("action")?.let { put("input_action", it) }
            args["pressed"]?.let { put("pressed", it) }
            args["position"]?.let { put("position", it) }
            args["relative"]?.let { put("relative", it) }
            args["strength"]?.let { put("strength", it) }
        }
        return bridgeResult(godot.call("simulate_input", body))
    }
}

private class GodotReloadScriptTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_reload_script"
    override val description =
        "Hot-reload a node's script after editing its .gd file with the normal file tools — snapshots " +
            "current property values, reassigns the script, then restores matching properties, so " +
            "in-progress state (e.g. health) survives the edit. Returns which properties were restored."
    override val parameters = objectSchema(
        Param("path", "string", "NodePath rooted at /root, from godot_scene_tree"),
        Param("script_path", "string", "res:// path of the (already-edited-on-disk) .gd script to attach"),
    )
    override val mutating = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("Missing 'path'", isError = true)
        val scriptPath = args.str("script_path") ?: return ToolResult("Missing 'script_path'", isError = true)
        return bridgeResult(
            godot.call(
                "reload_script",
                buildJsonObject {
                    put("path", path)
                    put("script_path", scriptPath)
                },
            ),
        )
    }
}

private class GodotCapabilitiesTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_capabilities"
    override val description = "Discover the exact Godot version, Orion bridge protocol, and every supported live-engine action."
    override val parameters = objectSchema()
    override suspend fun execute(args: JsonObject, ctx: ToolContext) = bridgeResult(godot.call("capabilities"))
}

private class GodotClassDbTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_classdb"
    override val description =
        "Query Godot's live ClassDB. Omit class_name for every engine class; pass one class for its parent, instantiability, properties, methods, and signals."
    override val parameters = objectSchema(Param("class_name", "string", "Godot class such as CharacterBody3D; omit to list all classes", required = false))
    override suspend fun execute(args: JsonObject, ctx: ToolContext) = bridgeResult(
        godot.call("classdb", buildJsonObject { args.str("class_name")?.let { put("class_name", it) } }),
    )
}

private class GodotSaveSceneTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_save_scene"
    override val description = "Persist the current live scene tree to disk. Newly created nodes are assigned scene ownership so they survive restart."
    override val parameters = objectSchema(Param("path", "string", "Optional res:// .tscn/.scn destination; defaults to current scene", required = false))
    override val mutating = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext) = bridgeResult(
        godot.call("save_scene", buildJsonObject { args.str("path")?.let { put("path", it) } }),
    )
}

private class GodotChangeSceneTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_change_scene"
    override val description = "Load another scene from this project into the running game."
    override val parameters = objectSchema(Param("path", "string", "Scene path such as res://levels/Level2.tscn"))
    override val mutating = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult("Missing 'path'", true)
        return bridgeResult(godot.call("change_scene", buildJsonObject { put("path", path) }))
    }
}

private class GodotProjectSettingTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_project_setting"
    override val description = "Read or persist one ProjectSettings value in project.godot. Include value to change it; omit value to read."
    override val parameters = objectSchema(
        Param("name", "string", "Setting key such as application/config/name or display/window/size/viewport_width"),
        Param("value", "string", "Natural JSON value to save; omit for read", required = false),
    )
    override val mutating = true
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args.str("name") ?: return ToolResult("Missing 'name'", true)
        return bridgeResult(godot.call("project_setting", buildJsonObject {
            put("name", name)
            args["value"]?.let { put("value", it) }
        }))
    }
}

private class GodotInputActionsTool(private val godot: GodotController) : AgentTool {
    override val name = "godot_input_actions"
    override val description = "List every live InputMap action name before simulating game input."
    override val parameters = objectSchema()
    override suspend fun execute(args: JsonObject, ctx: ToolContext) = bridgeResult(godot.call("input_actions"))
}
