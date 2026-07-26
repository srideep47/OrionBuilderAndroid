package com.legion.orion.util

import java.io.File

/**
 * Collision-avoidance: appends " (1)", " (2)", etc. before the extension until [name] is free in
 * [dir]. Shared by the File Manager's paste/duplicate/extract/import actions and the P2P asset
 * receiver, so all of them land files the same way.
 */
fun uniqueDestination(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isNotEmpty()) ".$it" else "" }
    var i = 1
    while (candidate.exists()) {
        candidate = File(dir, "$base ($i)$ext")
        i++
    }
    return candidate
}

/** Buckets an asset file name into the `assets/<category>` convention by extension. */
fun assetCategoryFor(fileName: String): String {
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "glb", "gltf", "fbx", "obj" -> "models"
        "png", "jpg", "jpeg", "webp", "exr", "hdr" -> "textures"
        "wav", "ogg", "mp3" -> "audio"
        else -> "other"
    }
}
