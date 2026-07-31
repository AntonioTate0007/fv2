package com.mark39.assistant.data

data class ChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis()
)
