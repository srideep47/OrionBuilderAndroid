package com.legion.orion.runtime.godoteditor

import org.godotengine.godot.GodotActivity

/**
 * Hosts the real Godot Editor UI, entirely inside its own isolated `:godot_editor` process (see
 * this module's AndroidManifest.xml). [GodotActivity] (from the vendored `godot-tools` AAR — the
 * custom SCons build with `disable_path_overrides=no`, see `runtime/godot/repo/README.md`) already
 * handles GodotFragment creation/lifecycle exactly as it does for the template flavor;
 * [EditorLaunch] builds the `--editor --path <dir>` command line via the same
 * `EXTRA_COMMAND_LINE_PARAMS` mechanism [com.legion.orion.runtime.godot.GodotLaunch] uses for the
 * game engine.
 *
 * Deliberately does NOT port `BaseGodotEditor`'s Play/GABE/XR/game-menu machinery (multi-window
 * IPC for the Play button, on-device Gradle APK export, XR game launching) — confirmed by reading
 * the real Godot source that the dock panels (Scene tree, FileSystem, Script editor, Inspector)
 * are the engine's own engine-drawn `Control`-node UI, rendered through this exact same
 * `GodotFragment` surface regardless of any of that extra Android-side scaffolding. A v1 that can
 * view/edit scenes and scripts needs none of it.
 */
class EditorHostActivity : GodotActivity()
