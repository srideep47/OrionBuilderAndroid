package com.legion.orion.runtime.godoteditor

import android.content.Context
import java.io.File

/**
 * Ensures a writable project directory exists for [EditorHostActivity] to open — the engine needs
 * to WRITE scene/script changes, which `runtime:godot`'s bundled `src/main/assets/` project
 * structurally can't support (it's read-only, baked into the APK). Seeded once from this module's
 * own `assets/editor_seed/` (a plain `project.godot` + `Main.tscn`, deliberately with no
 * `.gdextension`/bridge reference — the editor process never needs the AI-control bridge, only the
 * template/game engine process does), then left alone: the editor's own `.godot/` import cache and
 * any further edits persist across sessions in `context.filesDir`, same directory every time.
 */
object EditorProjectSeed {
    private const val PROJECT_DIR_NAME = "godot_editor_project"

    /** Returns the absolute path of the writable project directory, seeding it on first call. */
    fun ensureSeeded(context: Context): String {
        val dir = File(context.filesDir, PROJECT_DIR_NAME)
        if (!File(dir, "project.godot").isFile) {
            dir.mkdirs()
            context.assets.open("editor_seed/project.godot").use { input ->
                File(dir, "project.godot").outputStream().use { input.copyTo(it) }
            }
            context.assets.open("editor_seed/Main.tscn").use { input ->
                File(dir, "Main.tscn").outputStream().use { input.copyTo(it) }
            }
        }
        return dir.absolutePath
    }
}
