package com.mark39.assistant.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Mark's persistent memory: short facts the user asks it to remember ("remember that
 * my dog's name is Pepper"). Stored as a JSON string list and injected into the system
 * prompt so answers stay grounded in who the user is across sessions.
 */
object Memory {
    private val FACTS = stringPreferencesKey("memory_facts")
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_FACTS = 50

    fun flow(c: Context): Flow<List<String>> = c.dataStore.data.map { decode(it[FACTS]) }

    suspend fun all(c: Context): List<String> = decode(c.dataStore.data.first()[FACTS])

    suspend fun remember(c: Context, fact: String) {
        val clean = fact.trim()
        if (clean.isEmpty()) return
        c.dataStore.edit { prefs ->
            val cur = decode(prefs[FACTS]).toMutableList()
            cur.removeAll { it.equals(clean, ignoreCase = true) }
            cur += clean
            while (cur.size > MAX_FACTS) cur.removeAt(0)
            prefs[FACTS] = json.encodeToString(ListSerializer(String.serializer()), cur)
        }
    }

    suspend fun clear(c: Context) {
        c.dataStore.edit { it[FACTS] = json.encodeToString(ListSerializer(String.serializer()), emptyList()) }
    }

    private fun decode(raw: String?): List<String> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrDefault(emptyList())
}
