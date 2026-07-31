package com.fortress.app.jarvis

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fortress.app.data.preferences.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable list of pending reminders, kept as a JSON blob in the shared Fortress
 * DataStore. Everything is sorted by fire time so the UI and the boot re-scheduler
 * can walk them in order. Decoding always fails soft to an empty list — a corrupt
 * blob should never crash the app.
 */
object ReminderStore {
    private val REMINDERS = stringPreferencesKey("jarvis_reminders")
    private val json = Json { ignoreUnknownKeys = true }

    fun flow(context: Context): Flow<List<Reminder>> =
        context.dataStore.data.map { decode(it[REMINDERS]) }

    suspend fun all(context: Context): List<Reminder> =
        decode(context.dataStore.data.first()[REMINDERS])

    suspend fun add(context: Context, reminder: Reminder) {
        context.dataStore.edit { prefs ->
            val updated = (decode(prefs[REMINDERS]).filterNot { it.id == reminder.id } + reminder)
                .sortedBy { it.triggerAtMillis }
            prefs[REMINDERS] = json.encodeToString(updated)
        }
    }

    suspend fun remove(context: Context, id: String) {
        context.dataStore.edit { prefs ->
            prefs[REMINDERS] = json.encodeToString(decode(prefs[REMINDERS]).filterNot { it.id == id })
        }
    }

    /** Drop reminders whose fire time has already passed (e.g. fired while offline). */
    suspend fun pruneExpired(context: Context, now: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs ->
            prefs[REMINDERS] = json.encodeToString(decode(prefs[REMINDERS]).filter { it.triggerAtMillis > now })
        }
    }

    private fun decode(raw: String?): List<Reminder> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<Reminder>>(raw) }.getOrDefault(emptyList())
}
