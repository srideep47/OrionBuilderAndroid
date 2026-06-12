package com.legion.orion.runtime

import android.content.Context
import java.io.File

/** Recursively copies a bundled asset directory into a destination on disk (idempotent per [stamp]). */
object AssetExtractor {

    /** Extracts assets/[assetRoot] → [dest], skipping if a [stamp] marker already matches [version]. */
    fun extractOnce(context: Context, assetRoot: String, dest: File, version: String) {
        val marker = File(dest, ".extracted")
        if (marker.exists() && marker.readText() == version) return
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        copyDir(context, assetRoot, dest)
        marker.writeText(version)
    }

    private fun copyDir(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // It's a file: copy it.
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            val childAsset = "$assetPath/$child"
            val childDest = File(dest, child)
            val grandchildren = context.assets.list(childAsset) ?: emptyArray()
            if (grandchildren.isEmpty()) {
                context.assets.open(childAsset).use { input ->
                    childDest.outputStream().use { input.copyTo(it) }
                }
            } else {
                copyDir(context, childAsset, childDest)
            }
        }
    }
}
