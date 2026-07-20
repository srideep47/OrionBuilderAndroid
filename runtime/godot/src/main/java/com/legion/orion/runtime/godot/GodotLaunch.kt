package com.legion.orion.runtime.godot

import android.content.Context
import android.content.Intent
import org.godotengine.godot.GodotActivity

/**
 * Builds the launch [Intent] for [GodotHostActivity]. Keeps `org.godotengine.godot.*` fully
 * encapsulated inside this module — [com.legion.orion.runtime.GodotProcessController] (in :app)
 * never references Godot's own AAR classes directly, matching how nothing in :app implements
 * GodotHost either.
 */
object GodotLaunch {
    fun intentFor(context: Context, commandLineArgs: List<String>): Intent =
        Intent(context, GodotHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(GodotActivity.EXTRA_COMMAND_LINE_PARAMS, commandLineArgs.toTypedArray())
}
