package com.fortress.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fortress_prefs")

object AppPreferences {
    private val GEMINI_KEY = stringPreferencesKey("gemini_api_key")
    private val ALERTED_POSITION_IDS = stringSetPreferencesKey("alerted_position_ids")

    fun geminiKeyFlow(context: Context): Flow<String> =
        context.dataStore.data.map { it[GEMINI_KEY].orEmpty() }

    suspend fun saveGeminiKey(context: Context, key: String) {
        context.dataStore.edit { it[GEMINI_KEY] = key.trim() }
    }

    suspend fun clearGeminiKey(context: Context) {
        context.dataStore.edit { it.remove(GEMINI_KEY) }
    }

    fun alertedPositionIdsFlow(context: Context): Flow<Set<String>> =
        context.dataStore.data.map { it[ALERTED_POSITION_IDS].orEmpty() }

    suspend fun markAlerted(context: Context, positionId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[ALERTED_POSITION_IDS].orEmpty().toMutableSet()
            current += positionId
            prefs[ALERTED_POSITION_IDS] = current
        }
    }

    /** Drop ids that are no longer in the live positions list — keeps the set bounded. */
    suspend fun pruneAlerted(context: Context, livePositionIds: Set<String>) {
        context.dataStore.edit { prefs ->
            val current = prefs[ALERTED_POSITION_IDS].orEmpty()
            prefs[ALERTED_POSITION_IDS] = current.intersect(livePositionIds)
        }
    }
}
