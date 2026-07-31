package com.mark39.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-wide text-to-speech — the "out loud" half of Mark. The engine initializes
 * asynchronously, so anything asked to speak before it's ready is queued and flushed
 * on init. One shared instance keeps a warm engine for both the UI and alarm reminders.
 */
class VoiceManager private constructor(context: Context) {

    private val ready = AtomicBoolean(false)
    private val pending = ArrayDeque<String>()
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ready.set(true)
                synchronized(pending) { while (pending.isNotEmpty()) speakNow(pending.removeFirst()) }
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (ready.get()) speakNow(text) else synchronized(pending) { pending.addLast(text) }
    }

    fun stop() { runCatching { tts?.stop() } }

    private fun speakNow(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    companion object {
        @Volatile private var INSTANCE: VoiceManager? = null
        fun get(context: Context): VoiceManager =
            INSTANCE ?: synchronized(this) { INSTANCE ?: VoiceManager(context).also { INSTANCE = it } }
    }
}
