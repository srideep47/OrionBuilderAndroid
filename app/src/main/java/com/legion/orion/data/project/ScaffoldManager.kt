package com.legion.orion.data.project

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Copies a bundled scaffold template (assets/scaffolds/<id>) into a project directory. */
@Singleton
class ScaffoldManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Available template ids (must match asset folder names). */
    val templates = listOf("godot", "vite-react-ts", "static-web", "android-kt")

    fun scaffold(templateId: String, destDir: File) {
        require(templateId in templates) { "Unknown template: $templateId" }
        destDir.mkdirs()
        copyAsset("scaffolds/$templateId", destDir)
    }

    private fun copyAsset(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (child in children) {
            val childAsset = "$assetPath/$child"
            val childDest = File(dest, child)
            if ((context.assets.list(childAsset) ?: emptyArray()).isEmpty()) {
                context.assets.open(childAsset).use { input -> childDest.outputStream().use { input.copyTo(it) } }
            } else {
                copyAsset(childAsset, childDest)
            }
        }
    }
}
