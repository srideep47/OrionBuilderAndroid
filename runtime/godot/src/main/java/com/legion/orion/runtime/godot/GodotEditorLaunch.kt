package com.legion.orion.runtime.godot

import android.content.Context
import android.content.Intent
import org.godotengine.godot.GodotActivity

/** Launches the tools build as the editor; without `--editor` the same binary runs a project. */
object GodotEditorLaunch {
    fun intentFor(context: Context, projectDir: String): Intent =
        Intent(context, GodotEditorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(
                GodotActivity.EXTRA_COMMAND_LINE_PARAMS,
                arrayOf("--editor", "--path", projectDir),
            )
}
