package com.legion.orion.runtime.godot

import android.content.Context
import java.io.File

/**
 * Makes an Orion project runnable and controllable by the embedded Godot build.
 *
 * User-authored files are never replaced. A starter project is installed only when the directory
 * is not already a Godot project; Orion-owned bridge files are refreshed on every launch so an APK
 * upgrade cannot leave an older native protocol behind.
 */
object GodotProjectInstaller {
    fun prepare(context: Context, projectDir: File): File {
        projectDir.mkdirs()
        if (!File(projectDir, "project.godot").isFile) {
            copyAsset(context, "project.godot", File(projectDir, "project.godot"))
            copyAsset(context, "Main.tscn", File(projectDir, "Main.tscn"))
            seedAssetFolders(projectDir)
        }

        copyAsset(context, "orion_bridge.gdextension", File(projectDir, "orion_bridge.gdextension"))
        copyAsset(
            context,
            "bin/liborion_bridge.android.template_release.arm64.so",
            File(projectDir, "bin/liborion_bridge.android.template_release.arm64.so"),
        )
        copyAsset(context, ".godot/extension_list.cfg", File(projectDir, ".godot/extension_list.cfg"))
        File(projectDir, ".orion").mkdirs()
        return projectDir
    }

    /** Seeds the `res://assets/{models,textures,audio}` convention on first install only, so both
     * AI backends have a predictable place to look for user-supplied assets and the user has an
     * obvious place to navigate into before importing one. A `.gdkeep` placeholder keeps each folder
     * present if the project later becomes a git repo (git does not track empty directories). */
    private fun seedAssetFolders(projectDir: File) {
        listOf("models", "textures", "audio").forEach { category ->
            val dir = File(projectDir, "assets/$category").apply { mkdirs() }
            File(dir, ".gdkeep").createNewFile()
        }
    }

    private fun copyAsset(context: Context, assetPath: String, destination: File) {
        destination.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
