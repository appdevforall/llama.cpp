package com.example.llama

data class ToolCall(val toolName: String, val args: Map<String, Any>)
