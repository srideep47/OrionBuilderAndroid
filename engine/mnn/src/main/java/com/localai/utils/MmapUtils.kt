// Simplified port of GPTee's MmapUtils: model-source-specific cache layout reduced to
// local/ + downloaded; the downloader integration returns in the marketplace phase.
package com.localai.utils

import com.alibaba.mls.api.ApplicationProvider
import com.localai.model.ModelUtils
import java.io.File

object MmapUtils {

    fun clearMmapCache(modelId: String): Boolean {
        return FileUtils.deleteDirectoryRecursively(File(getMmapDir(modelId)))
    }

    fun getMmapDir(modelId: String): String {
        val filesDir = ApplicationProvider.get().filesDir.toString()
        val safeId = ModelUtils.safeModelId(
            if (modelId.startsWith("local/")) modelId else modelId.substringAfter("/", modelId),
        )
        return if (modelId.startsWith("local/")) {
            "$filesDir/local_temps/$safeId"
        } else {
            "$filesDir/tmps/$safeId"
        }
    }
}
