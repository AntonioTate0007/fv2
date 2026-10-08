package com.jarvis.glasses

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Always-on hotwords using Picovoice Porcupine, running entirely on the phone
 * (audio never leaves the device). Needs a free AccessKey from https://console.picovoice.ai/.
 *
 * - "Jarvis" is built into Porcupine and wakes the Jarvis persona.
 * - "Ultron" wakes the Ultron persona once you've trained the word (free) in the
 *   Picovoice console for Android and imported the .ppn file in the app.
 */
class WakeWord(private val context: Context, private val onWake: (Persona) -> Unit) {

    private var manager: PorcupineManager? = null
    private var personas: List<Persona> = emptyList()
    private var running = false
    private val main = Handler(Looper.getMainLooper())

    /** Null when the wake words are ready, otherwise a human-readable reason they aren't. */
    var problem: String? = null
        private set

    /** Which personas currently have a working wake word. */
    val listeningFor: List<Persona>
        get() = if (manager != null) personas else emptyList()

    fun init(accessKey: String) {
        release()
        if (accessKey.isBlank()) {
            problem = "No Picovoice key: wake words are off. Use the glasses tap or notification instead."
            return
        }
        val ultron = ultronKeywordFile(context).takeIf { it.exists() }
        val jarvis = extractBuiltInJarvis()

        problem = runCatching {
            val builder = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setErrorCallback { e -> Log.w(TAG, "Porcupine error", e) }
            personas = if (ultron != null && jarvis != null) {
                builder.setKeywordPaths(arrayOf(jarvis.path, ultron.path)).setSensitivities(floatArrayOf(0.6f, 0.6f))
                listOf(Persona.JARVIS, Persona.ULTRON)
            } else {
                builder.setKeyword(Porcupine.BuiltInKeyword.JARVIS).setSensitivity(0.6f)
                listOf(Persona.JARVIS)
            }
            manager = builder.build(context) { index ->
                val persona = personas.getOrElse(index) { Persona.JARVIS }
                main.post { onWake(persona) }
            }
        }.exceptionOrNull()?.let { e ->
            personas = emptyList()
            if (ultron != null) "Wake words failed to load (is ultron.ppn trained for Android?): ${e.message}"
            else "Wake word failed to load: ${e.message}"
        }
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

    /** Copies Porcupine's bundled jarvis.ppn to a file so it can sit alongside a custom keyword. */
    @SuppressLint("DiscouragedApi")
    private fun extractBuiltInJarvis(): File? = runCatching {
        val id = context.resources.getIdentifier("jarvis", "raw", context.packageName)
        require(id != 0) { "jarvis.ppn not bundled" }
        val out = File(context.filesDir, "jarvis.ppn")
        context.resources.openRawResource(id).use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }.onFailure { Log.w(TAG, "Couldn't extract built-in jarvis keyword", it) }.getOrNull()

    companion object {
        private const val TAG = "WakeWord"

        /** Where an imported "Ultron" keyword lives. */
        fun ultronKeywordFile(context: Context) = File(context.filesDir, "ultron.ppn")
    }
}
