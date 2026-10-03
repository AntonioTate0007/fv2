package com.thumbshade.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SettingsRepo {
    private const val KEY = "json"
    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state
    val current: AppSettings get() = _state.value

    fun init(context: Context) {
        val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        p.getString(KEY, null)
            ?.let { runCatching { AppJson.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
            ?.let { _state.value = it }
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        prefs?.edit()?.putString(KEY, AppJson.encodeToString(AppSettings.serializer(), next))?.apply()
    }

    fun replace(settings: AppSettings) = update { settings }
}
