package com.legion.orion.engine.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A single parameter: name, JSON-schema type, description, and whether it's required. */
data class Param(val name: String, val type: String, val description: String, val required: Boolean = true)

/** Builds a JSON-Schema object for a tool's parameters from a flat list of [params]. */
fun objectSchema(vararg params: Param): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        params.forEach { p ->
            putJsonObject(p.name) {
                put("type", p.type)
                put("description", p.description)
            }
        }
    }
    putJsonArray("required") {
        params.filter { it.required }.forEach { add(it.name) }
    }
}
