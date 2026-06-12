package com.legion.orion.engine.providers.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Best-effort repair of the not-quite-JSON small local models sometimes emit for tool calls.
 * Applies cheap, well-known fixes (strip code fences, drop trailing commas, quote bare keys,
 * normalize quotes) and re-parses. Returns the parsed element or null after [maxRounds].
 */
object JsonRepair {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String, maxRounds: Int = 2): JsonElement? {
        var candidate = stripFences(raw).trim()
        repeat(maxRounds + 1) { round ->
            runCatching { return lenient.parseToJsonElement(candidate) }
            if (round == maxRounds) return null
            candidate = repair(candidate)
        }
        return null
    }

    private fun stripFences(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```")
            // optional language tag (json) on the first line
            val nl = t.indexOf('\n')
            if (nl in 0..10 && t.take(nl).all { it.isLetter() }) t = t.substring(nl + 1)
            val fence = t.lastIndexOf("```")
            if (fence >= 0) t = t.substring(0, fence)
        }
        // Trim to the outermost JSON object/array if there's surrounding prose.
        val start = t.indexOfFirst { it == '{' || it == '[' }
        val end = t.indexOfLast { it == '}' || it == ']' }
        return if (start in 0..end) t.substring(start, end + 1) else t
    }

    private fun repair(s: String): String {
        var t = s
        // Drop trailing commas before } or ]
        t = t.replace(Regex(",\\s*([}\\]])"), "$1")
        // Quote unquoted object keys:  {name: ...} -> {"name": ...}
        t = t.replace(Regex("([{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)(\\s*:)"), "$1\"$2\"$3")
        // Normalize fancy quotes
        t = t.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
        return t
    }
}
