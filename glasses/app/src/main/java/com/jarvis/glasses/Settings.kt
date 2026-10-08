package com.jarvis.glasses

import android.content.Context

/** Everything the user can tweak, persisted in private SharedPreferences on the phone. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var xaiKey: String
        get() = prefs.getString(KEY_XAI, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_XAI_KEY
        set(v) = prefs.edit().putString(KEY_XAI, v.trim()).apply()

    var picovoiceKey: String
        get() = prefs.getString(KEY_PICO, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_PICOVOICE_KEY
        set(v) = prefs.edit().putString(KEY_PICO, v.trim()).apply()

    /** Any vision-capable Grok model id from https://docs.x.ai/developers/models */
    var model: String
        get() = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString(KEY_MODEL, v.trim()).apply()

    /** Grok TTS voice: leo (authoritative), rex (confident), sal (balanced), ara (warm), eve (upbeat). */
    var voice: String
        get() = prefs.getString(KEY_VOICE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE
        set(v) = prefs.edit().putString(KEY_VOICE, v.trim().lowercase()).apply()

    /** What Jarvis calls you. */
    var honorific: String
        get() = prefs.getString(KEY_HONORIFIC, null)?.takeIf { it.isNotBlank() } ?: "sir"
        set(v) = prefs.edit().putString(KEY_HONORIFIC, v.trim()).apply()

    /** Use Grok's own voice (xAI TTS). Off = the phone's built-in British voice (free, offline). */
    var grokVoice: Boolean
        get() = prefs.getBoolean(KEY_GROK_VOICE, true)
        set(v) = prefs.edit().putBoolean(KEY_GROK_VOICE, v).apply()

    /** After answering, keep listening a few seconds for a follow-up without the wake word. */
    var followUps: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_UPS, true)
        set(v) = prefs.edit().putBoolean(KEY_FOLLOW_UPS, v).apply()

    /** Send a photo from the glasses camera with each question. */
    var useCamera: Boolean
        get() = prefs.getBoolean(KEY_CAMERA, true)
        set(v) = prefs.edit().putBoolean(KEY_CAMERA, v).apply()

    companion object {
        const val DEFAULT_MODEL = "grok-4.7"
        const val DEFAULT_VOICE = "leo"

        private const val KEY_XAI = "xai_key"
        private const val KEY_PICO = "picovoice_key"
        private const val KEY_MODEL = "model"
        private const val KEY_VOICE = "voice"
        private const val KEY_HONORIFIC = "honorific"
        private const val KEY_GROK_VOICE = "grok_voice"
        private const val KEY_FOLLOW_UPS = "follow_ups"
        private const val KEY_CAMERA = "camera"
    }
}
