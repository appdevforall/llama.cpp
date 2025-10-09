package com.example.llama

import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.util.regex.Pattern

object Util {

    fun parseToolCall(responseText: String, toolKeys: Set<String>): ToolCall? {
        val jsonString = findPotentialJsonObjectString(responseText)
        if (jsonString != null) {
            val toolCallFromJson = parseJsonObjectToToolCall(jsonString)
            if (toolCallFromJson != null) {
                Log.d("ToolParse", "Successfully parsed tool call from well-formed JSON.")
                return toolCallFromJson
            }
        }
        Log.w(
            "ToolParse",
            "Could not parse JSON. Attempting recovery by searching for tool name..."
        )
        val tagContent =
            responseText.substringAfter("<tool_call>", "").substringBefore("</tool_call>", "")
                .trim()
        if (tagContent.isNotBlank()) {
            for (toolName in toolKeys) {
                if (tagContent.contains(toolName)) {
                    Log.d(
                        "ToolParse",
                        "RECOVERY SUCCESS: Found tool name '$toolName' in malformed output."
                    )
                    return ToolCall(toolName, emptyMap())
                }
            }
        }
        Log.e(
            "ToolParse",
            "RECOVERY FAILED: No valid JSON or known tool name found in the response."
        )
        return null
    }

    private fun findPotentialJsonObjectString(responseText: String): String? {
        var candidateString = responseText
        val tagPattern = Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL)
        val tagMatcher = tagPattern.matcher(responseText)
        if (tagMatcher.find()) {
            candidateString = tagMatcher.group(1) ?: ""
        }
        val firstBraceIndex = candidateString.indexOf('{')
        val lastBraceIndex = candidateString.lastIndexOf('}')
        if (firstBraceIndex != -1 && lastBraceIndex != -1 && firstBraceIndex < lastBraceIndex) {
            return candidateString.substring(firstBraceIndex, lastBraceIndex + 1)
        }
        return null
    }

    private fun parseJsonObjectToToolCall(jsonStr: String): ToolCall? {
        return try {
            val json = JSONObject(jsonStr)

            // Safely get the tool name. If it's missing or empty, it's not a valid tool call.
            val toolName = json.optString("tool_name", "")
            if (toolName.isBlank()) {
                Log.w("ToolParse", "JSON is missing a valid 'tool_name'.")
                return null
            }

            // Safely get the args object. optJSONObject returns null if it's missing.
            val argsJson = json.optJSONObject("args")
            val argsMap = mutableMapOf<String, Any>()

            // *** THIS IS THE CRITICAL FIX ***
            // We explicitly check if argsJson is not null before trying to access its keys.
            // This prevents the NullPointerException that is crashing your coroutine.
            argsJson?.keys()?.forEach { key ->
                // Also check that the value for the key is not null before adding it
                if (argsJson.has(key) && !argsJson.isNull(key)) {
                    argsMap[key] = argsJson.get(key)
                }
            }

            ToolCall(toolName, argsMap)
        } catch (e: JSONException) {
            // This will catch any other errors if the string is not valid JSON
            Log.e("ToolParse", "Failed to parse JSON string to ToolCall: $jsonStr", e)
            null
        }
    }

}
