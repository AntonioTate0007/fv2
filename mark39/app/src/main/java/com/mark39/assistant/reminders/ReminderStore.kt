package com.mark39.assistant.reminders

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mark39.assistant.data.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Durable list of pending reminders, kept as JSON in DataStore, sorted by fire time. */
object ReminderStore {
    private val REMINDERS = stringPreferencesKey("reminders")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Reminder.serializer())

    fun flow(c: Context): Flow<List<Reminder>> = c.dataStore.data.map { decode(it[REMINDERS]) }

    suspend fun all(c: Context): List<Reminder> = decode(c.dataStore.data.first()[REMINDERS])

    suspend fun add(c: Context, r: Reminder) {
        c.dataStore.edit { prefs ->
            val updated = (decode(prefs[REMINDERS]).filterNot { it.id == r.id } + r)
                .sortedBy { it.triggerAtMillis }
            prefs[REMINDERS] = json.encodeToString(serializer, updated)
        }
    }

    suspend fun remove(c: Context, id: String) {
        c.dataStore.edit { prefs ->
            prefs[REMINDERS] = json.encodeToString(serializer, decode(prefs[REMINDERS]).filterNot { it.id == id })
        }
    }

    suspend fun pruneExpired(c: Context, now: Long = System.currentTimeMillis()) {
        c.dataStore.edit { prefs ->
            prefs[REMINDERS] = json.encodeToString(serializer, decode(prefs[REMINDERS]).filter { it.triggerAtMillis > now })
        }
    }

    private fun decode(raw: String?): List<Reminder> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
}
