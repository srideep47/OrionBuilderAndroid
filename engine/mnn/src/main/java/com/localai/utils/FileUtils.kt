// Minimal port of GPTee's FileUtils — only what ModelConfig needs.
package com.localai.utils

import java.io.File

object FileUtils {

    fun ensureParentDirectoriesExist(file: File) {
        file.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
    }

    fun deleteDirectoryRecursively(dir: File): Boolean {
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }
}
