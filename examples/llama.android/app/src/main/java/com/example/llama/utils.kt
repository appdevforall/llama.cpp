package com.example.llama

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.regex.Pattern

object Util {

    fun parseToolCall(responseText: String, toolKeys: Set<String>): ToolCall? {
        println("--- PARSER START ---")
        println("Input responseText: '$responseText'")
        val jsonString = findPotentialJsonObjectString(responseText)
        println("findPotentialJsonObjectString returned: '$jsonString'")
        if (jsonString != null) {
            val toolCallFromJson = parseJsonObjectToToolCall(jsonString)
            println("parseJsonObjectToToolCall returned: $toolCallFromJson")
            if (toolCallFromJson != null) {
                println("SUCCESS: Returning valid tool call from JSON.")
                return toolCallFromJson
            }
        }

        Log.e("TOOL_DEBUG", "FAILURE: Falling back to recovery or returning null.")
        val tagContent =
            responseText.substringAfter("<tool_call>", "").substringBefore("</tool_call>").trim()
        if (tagContent.isNotBlank()) {
            for (toolName in toolKeys) {
                if (tagContent.contains("\"$toolName\"")) { // Be more specific to avoid accidental matches
                    println(
                        "RECOVERY SUCCESS: Found tool name '$toolName' in malformed output."
                    )
                    return ToolCall(toolName, emptyMap())
                }
            }
        }

        Log.e("ToolParse", "RECOVERY FAILED: No valid JSON or known tool name found in response.")
        return null
    }

    private fun findPotentialJsonObjectString(responseText: String): String? {
        val tagPattern = Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL)
        val tagMatcher = tagPattern.matcher(responseText)
        val candidateString = if (tagMatcher.find()) {
            tagMatcher.group(1) ?: ""
        } else {
            responseText
        }
        val firstBraceIndex = candidateString.indexOf('{')
        val lastBraceIndex = candidateString.lastIndexOf('}')
        if (firstBraceIndex != -1 && lastBraceIndex != -1 && firstBraceIndex < lastBraceIndex) {
            return candidateString.substring(firstBraceIndex, lastBraceIndex + 1)
        }
        return null
    }

    private val jsonParser = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun parseJsonObjectToToolCall(jsonStr: String): ToolCall? {
        return try {
            // Use the new parser to create a generic JSON element
            val jsonElement = jsonParser.parseToJsonElement(jsonStr)

            // Safely access the "tool_name" property
            val toolName = jsonElement.jsonObject["tool_name"]?.jsonPrimitive?.content ?: ""

            if (toolName.isBlank()) {
                return null
            }

            // Since the 'args' are always empty in our tests, we can simplify this for now.
            val argsMap = emptyMap<String, Any>()

            ToolCall(toolName, argsMap)
        } catch (e: Exception) {
            // Catch any parsing exceptions from the new library
            println("TOOL_DEBUG: Kotlinx Serialization failed to parse JSON: ${e.message}")
            null
        }
    }
}
