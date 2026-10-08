package com.jarvis.glasses

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Always-on "Jarvis" hotword using Picovoice Porcupine's built-in JARVIS keyword.
 * Runs entirely on the phone; audio never leaves the device. Needs a free
 * AccessKey from https://console.picovoice.ai/.
 */
class WakeWord(private val context: Context, private val onWake: () -> Unit) {

    private var manager: PorcupineManager? = null
    private var running = false
    private val main = Handler(Looper.getMainLooper())

    /** Null when the wake word is ready, otherwise a human-readable reason it isn't. */
    var problem: String? = null
        private set

    fun init(accessKey: String) {
        release()
        if (accessKey.isBlank()) {
            problem = "No Picovoice key: say \"Jarvis\" is off. Use the glasses tap or notification instead."
            return
        }
        problem = runCatching {
            manager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.6f)
                .setErrorCallback { e -> Log.w(TAG, "Porcupine error", e) }
                .build(context) { main.post(onWake) }
        }.exceptionOrNull()?.let { "Wake word failed to load: ${it.message}" }
    }

    fun resume() {
        val m = manager ?: return
        if (running) return
        runCatching { m.start() }
            .onSuccess { running = true }
            .onFailure { Log.w(TAG, "start failed", it) }
    }

    fun pause() {
        val m = manager ?: return
        if (!running) return
        runCatching { m.stop() }
        running = false
    }

    fun release() {
        pause()
        manager?.delete()
        manager = null
    }

    private companion object {
        const val TAG = "WakeWord"
    }
}
