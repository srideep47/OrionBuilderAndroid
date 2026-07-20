package com.legion.orion.runtime.godoteditor

import android.content.Context
import android.content.Intent
import org.godotengine.godot.GodotActivity

/**
 * Builds the launch [Intent] for [EditorHostActivity] — keeps `org.godotengine.godot.*` fully
 * encapsulated inside this module, mirroring [com.legion.orion.runtime.godot.GodotLaunch].
 */
object EditorLaunch {
    fun intentFor(context: Context, projectDir: String): Intent =
        Intent(context, EditorHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(
                GodotActivity.EXTRA_COMMAND_LINE_PARAMS,
                arrayOf("--editor", "--path", projectDir),
            )
}
