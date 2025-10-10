package com.example.llama

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@InternalSerializationApi
@Serializable
data class ToolCall(val name: String, val args: Map<String, String>)
