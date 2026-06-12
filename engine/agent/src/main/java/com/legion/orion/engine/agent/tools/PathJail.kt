package com.legion.orion.engine.agent.tools

import java.io.File
import java.io.IOException

/** Resolves a tool-supplied relative path strictly inside [root], rejecting traversal escapes. */
object PathJail {
    @Throws(IOException::class)
    fun resolve(root: File, relative: String): File {
        val cleaned = relative.trim().removePrefix("/").removePrefix("./")
        val target = File(root, cleaned).canonicalFile
        val base = root.canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator)) {
            throw IOException("Path '$relative' escapes the project directory")
        }
        return target
    }
}
