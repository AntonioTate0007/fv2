package com.fortress.app.jarvis

import kotlinx.serialization.Serializable

/**
 * A single thing Jarvis will remind you about. Persisted as JSON in DataStore and
 * scheduled as an exact alarm so it fires even if the app is closed or the phone
 * was rebooted (we re-arm everything on boot).
 */
@Serializable
data class Reminder(
    val id: String,
    val message: String,
    val triggerAtMillis: Long,
    val createdAtMillis: Long = System.currentTimeMillis()
)
