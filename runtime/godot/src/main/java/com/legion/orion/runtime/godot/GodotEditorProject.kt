package com.legion.orion.runtime.godot

import android.content.Context
import java.io.File

/** Creates a writable starter project once; subsequent editor changes are preserved. */
object GodotEditorProject {
    private const val PROJECT_DIR_NAME = "godot_editor_project"

    fun ensureSeeded(context: Context): File {
        val dir = File(context.filesDir, PROJECT_DIR_NAME)
        val project = File(dir, "project.godot")
        if (!project.isFile) {
            dir.mkdirs()
            project.writeText(
                """
                [application]
                config/name="Orion Godot Project"
                run/main_scene="res://Main.tscn"

                [display]
                window/size/viewport_width=1280
                window/size/viewport_height=720

                [rendering]
                renderer/rendering_method="mobile"
                renderer/rendering_method.mobile="mobile"
                """.trimIndent() + "\n",
            )
            File(dir, "Main.tscn").writeText(
                """
                [gd_scene format=3]

                [node name="Main" type="Node2D"]
                """.trimIndent() + "\n",
            )
        }
        return dir
    }
}
