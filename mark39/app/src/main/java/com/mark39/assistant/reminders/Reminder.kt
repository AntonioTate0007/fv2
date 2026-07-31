package com.mark39.assistant.reminders

import kotlinx.serialization.Serializable

@Serializable
data class Reminder(
    val id: String,
    val message: String,
    val triggerAtMillis: Long,
    val createdAtMillis: Long = System.currentTimeMillis()
)
