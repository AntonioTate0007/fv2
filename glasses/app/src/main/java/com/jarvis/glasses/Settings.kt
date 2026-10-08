package com.jarvis.glasses

import android.content.Context

/** Who's answering. Each persona has its own personality, Grok voice and (optionally) wake word. */
enum class Persona(val displayName: String, val defaultVoice: String) {
    /** Polite British butler. Wake word "Jarvis" (built into Porcupine). */
    JARVIS("Jarvis", "leo"),

    /** Cold, theatrical, menacing — but still helpful. Wake word "Ultron" needs a custom keyword file. */
    ULTRON("Ultron", "rex");

    companion object {
        fun parse(s: String?): Persona = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: JARVIS
    }
}

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

    /**
     * Persona used when Jarvis is summoned without a name (touchpad tap, notification,
     * glasses button). Saying a persona's wake word always picks that persona.
     */
    var persona: Persona
        get() = Persona.parse(prefs.getString(KEY_PERSONA, null))
        set(v) = prefs.edit().putString(KEY_PERSONA, v.name).apply()

    /** Grok TTS voice per persona: leo (authoritative), rex (confident), sal (balanced), ara (warm), eve (upbeat). */
    fun voiceFor(p: Persona): String =
        prefs.getString(KEY_VOICE_PREFIX + p.name, null)?.takeIf { it.isNotBlank() } ?: p.defaultVoice

    fun setVoice(p: Persona, voice: String) =
        prefs.edit().putString(KEY_VOICE_PREFIX + p.name, voice.trim().lowercase()).apply()

    /** What Jarvis calls you. */
    var honorific: String
        get() = prefs.getString(KEY_HONORIFIC, null)?.takeIf { it.isNotBlank() } ?: "sir"
        set(v) = prefs.edit().putString(KEY_HONORIFIC, v.trim()).apply()

    /** Use Grok's own voice (xAI TTS). Off = the phone's built-in voice (free, offline). */
    var grokVoice: Boolean
        get() = prefs.getBoolean(KEY_GROK_VOICE, true)
        set(v) = prefs.edit().putBoolean(KEY_GROK_VOICE, v).apply()

    /** After answering, keep listening a few seconds for a follow-up without the wake word. */
    var followUps: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_UPS, true)
        set(v) = prefs.edit().putBoolean(KEY_FOLLOW_UPS, v).apply()

    /** Send what the glasses camera sees with each question. */
    var useCamera: Boolean
        get() = prefs.getBoolean(KEY_CAMERA, true)
        set(v) = prefs.edit().putBoolean(KEY_CAMERA, v).apply()

    /** Send several frames (a short clip) instead of just the sharpest photo. */
    var videoMode: Boolean
        get() = prefs.getBoolean(KEY_VIDEO, false)
        set(v) = prefs.edit().putBoolean(KEY_VIDEO, v).apply()

    /** Let Grok search the web and X for current information. */
    var webSearch: Boolean
        get() = prefs.getBoolean(KEY_SEARCH, true)
        set(v) = prefs.edit().putBoolean(KEY_SEARCH, v).apply()

    companion object {
        const val DEFAULT_MODEL = "grok-4.7"

        private const val KEY_XAI = "xai_key"
        private const val KEY_PICO = "picovoice_key"
        private const val KEY_MODEL = "model"
        private const val KEY_VOICE_PREFIX = "voice_"
        private const val KEY_HONORIFIC = "honorific"
        private const val KEY_PERSONA = "persona"
        private const val KEY_GROK_VOICE = "grok_voice"
        private const val KEY_FOLLOW_UPS = "follow_ups"
        private const val KEY_CAMERA = "camera"
        private const val KEY_VIDEO = "video"
        private const val KEY_SEARCH = "web_search"
    }
}
