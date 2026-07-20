package com.legion.orion.runtime.godot

import android.content.Context
import java.io.File

/**
 * Materializes the APK's demo/AI-bridge project into writable app storage. Editor-capable Godot
 * builds open the Project Manager when launched without a path, so every preview launch points at
 * this directory explicitly.
 */
object GodotPreviewProject {
    private const val PROJECT_DIR_NAME = "godot_preview_project"

    fun ensureSeeded(context: Context): File {
        val dir = File(context.filesDir, PROJECT_DIR_NAME)
        return GodotProjectInstaller.prepare(context, dir)
    }
}
