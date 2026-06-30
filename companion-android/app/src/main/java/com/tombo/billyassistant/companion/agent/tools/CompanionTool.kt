package com.tombo.billyassistant.companion.agent.tools

import org.json.JSONArray
import org.json.JSONObject

interface CompanionTool {
    val declarations: List<JSONObject>

    fun execute(name: String, args: JSONObject): CompanionToolExecution?
}

data class CompanionToolExecution(
    val response: JSONObject,
    val followUpParts: JSONArray = JSONArray(),
    val finalText: String? = null,
    val watchImage: WatchImage? = null,
    val watchWeatherCurrent: WatchWeatherCurrent? = null,
    val clarificationCard: ClarificationCard? = null,
)

data class WatchImage(
    val width: Int,
    val height: Int,
    val data: ByteArray,
)

data class WatchWeatherCurrent(
    val temperature: Int,
    val feelsLike: Int,
    val location: String,
    val description: String,
    val tempUnit: String,
    val windSpeed: Int,
    val windSpeedUnit: String,
    val condition: Int,
)

data class ClarificationCard(
    val question: String,
    val context: String,
    val options: List<String>,
)

data class WatchMediaSpec(
    val maxWidth: Int,
    val maxHeight: Int,
    val pbiDepth: Int,
    val maxBytes: Int,
) {
    companion object {
        val Default = WatchMediaSpec(
            maxWidth = 144,
            maxHeight = 100,
            pbiDepth = 2,
            maxBytes = 23_000,
        )
    }
}

class CompanionToolRegistry(
    private val tools: List<CompanionTool>,
) {
    fun declarations(): JSONArray {
        val declarations = JSONArray()
        tools.forEach { tool ->
            tool.declarations.forEach { declaration -> declarations.put(declaration) }
        }
        return declarations
    }

    fun execute(name: String, args: JSONObject): CompanionToolExecution {
        tools.forEach { tool ->
            val result = tool.execute(name, args)
            if (result != null) {
                return result
            }
        }
        return CompanionToolExecution(
            JSONObject()
                .put("status", "error")
                .put("summary", "Unknown companion tool: $name"),
        )
    }
}

internal fun objectSchema(required: List<String>, properties: Map<String, JSONObject>): JSONObject {
    val propertyJson = JSONObject()
    properties.forEach { (name, schema) -> propertyJson.put(name, schema) }
    return JSONObject()
        .put("type", "object")
        .put("properties", propertyJson)
        .put("required", JSONArray(required))
}

internal fun stringSchema(description: String): JSONObject {
    return JSONObject()
        .put("type", "string")
        .put("description", description)
}

internal fun enumStringSchema(description: String, values: List<String>): JSONObject {
    return stringSchema(description)
        .put("enum", JSONArray(values))
}

internal fun integerSchema(description: String): JSONObject {
    return JSONObject()
        .put("type", "integer")
        .put("format", "int64")
        .put("description", description)
}

internal fun numberSchema(description: String): JSONObject {
    return JSONObject()
        .put("type", "number")
        .put("description", description)
}

internal fun booleanSchema(description: String): JSONObject {
    return JSONObject()
        .put("type", "boolean")
        .put("description", description)
}
