package com.example.llama

data class UiMessage(
    val id: Long,
    val text: String,
    val type: MessageType,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long? = null
)
