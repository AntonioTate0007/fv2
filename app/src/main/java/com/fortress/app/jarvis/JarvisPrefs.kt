package com.fortress.app.jarvis

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.fortress.app.data.preferences.dataStore
import kotlinx.coroutines.flow.first

/** Small knobs for the Jarvis assistant — currently the daily-briefing schedule. */
object JarvisPrefs {
    private val BRIEFING_ENABLED = booleanPreferencesKey("jarvis_briefing_enabled")
    private val BRIEFING_HOUR = intPreferencesKey("jarvis_briefing_hour")

    const val DEFAULT_BRIEFING_HOUR = 8

    suspend fun briefingEnabled(context: Context): Boolean =
        context.dataStore.data.first()[BRIEFING_ENABLED] ?: true

    suspend fun briefingHour(context: Context): Int =
        context.dataStore.data.first()[BRIEFING_HOUR] ?: DEFAULT_BRIEFING_HOUR

    suspend fun setBriefingEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[BRIEFING_ENABLED] = enabled }
    }

    suspend fun setBriefingHour(context: Context, hour: Int) {
        context.dataStore.edit { it[BRIEFING_HOUR] = hour.coerceIn(0, 23) }
    }
}
