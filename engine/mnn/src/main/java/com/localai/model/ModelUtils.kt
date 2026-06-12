// Minimal port of GPTee's ModelUtils — only what the engine layer needs.
package com.localai.model

object ModelUtils {

    fun safeModelId(modelId: String): String {
        return modelId.replace("/".toRegex(), "_")
    }
}
