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

        // Recovery logic for malformed JSON
        val tagContent =
            responseText.substringAfter("<tool_call>", "").substringBefore("</tool_call>").trim()
        if (tagContent.isNotBlank()) {
            for (toolName in toolKeys) {
                if (tagContent.contains("\"$toolName\"")) { // Be more specific to avoid accidental matches
                    Log.d(
                        "ToolParse",
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

    private fun parseJsonObjectToToolCall(jsonStr: String): ToolCall? {
        return try {
            val json = JSONObject(jsonStr)
            val toolName = json.optString("tool_name", "") ?: return null
            if (toolName.isBlank()) {
                return null
            }
            val argsJson = json.optJSONObject("args")
            val argsMap = mutableMapOf<String, Any>()

            // This 'let' block is the critical fix. It creates a non-nullable 'nonNullArgs'
            // variable, which prevents the NullPointerException. Your current code
            // is likely missing this, causing the crash.
            argsJson?.let { nonNullArgs ->
                nonNullArgs.keys().forEach { key ->
                    if (nonNullArgs.has(key) && !nonNullArgs.isNull(key)) {
                        argsMap[key] = nonNullArgs.get(key)
                    }
                }
            }

            ToolCall(toolName, argsMap)
        } catch (e: JSONException) {
            null
        }
    }

}
