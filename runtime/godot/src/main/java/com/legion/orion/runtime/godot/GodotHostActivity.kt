package com.legion.orion.runtime.godot

import org.godotengine.godot.GodotActivity

/**
 * Hosts Godot entirely inside the isolated :godot process (see this module's AndroidManifest.xml),
 * as a normal full-screen task — nothing in :app implements GodotHost or touches a GodotFragment
 * directly. [GodotActivity] (from the vendored godot-lib AAR) already handles GodotFragment
 * creation/reuse, lifecycle delegation, and reading engine command-line params from
 * [org.godotengine.godot.GodotActivity.EXTRA_COMMAND_LINE_PARAMS] on this non-exported activity —
 * see [GodotProcessController][com.legion.orion.runtime.GodotProcessController], which builds those
 * params from [GodotRendererPolicy] plus the target project directory.
 *
 * A Compose-embedded panel (SurfaceControlViewHost) hosting Godot inside Orion's own UI is a later
 * phase; this full-screen task is the deliberately simpler v1 surface.
 */
class GodotHostActivity : GodotActivity()
