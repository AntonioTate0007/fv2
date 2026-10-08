package com.jarvis.glasses

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Plays Jarvis's voice. Audio goes out as normal media, so with the glasses
 * connected over Bluetooth it comes out of the glasses' open-ear speakers.
 */
class Speaker(private val context: Context, private val settings: Settings) {

    private val audio = context.getSystemService(AudioManager::class.java)!!
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA) // media reliably routes to Bluetooth A2DP (the glasses)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    private var player: MediaPlayer? = null
    private var done: CompletableDeferred<Unit>? = null

    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()

    init {
        tts = TextToSpeech(context) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) configureBritishVoice()
            ttsReady.complete(ok)
        }
    }

    /** Plays MP3 bytes (Grok's voice) and suspends until finished or [stop]ped. */
    suspend fun playMp3(mp3: ByteArray) {
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "jarvis_reply.mp3").apply { writeBytes(mp3) }
        }
        withContext(Dispatchers.Main) {
            stop()
            val finished = CompletableDeferred<Unit>()
            done = finished
            audio.requestAudioFocus(focus)
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(file.path)
                setOnCompletionListener { finished.complete(Unit) }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error $what/$extra")
                    finished.complete(Unit)
                    true
                }
                prepare()
                start()
            }
        }
        try {
            done?.await()
        } finally {
            withContext(Dispatchers.Main) { release() }
        }
    }

    /** Speaks [text] with the phone's own (offline) text-to-speech engine. */
    suspend fun sayLocally(text: String) {
        if (!ttsReady.await()) return
        val engine = tts ?: return
        val finished = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            stop()
            done = finished
            audio.requestAudioFocus(focus)
            engine.setAudioAttributes(attrs)
            // Ultron: slower and much deeper than JARVIS.
            engine.setPitch(if (settings.isUltron) 0.6f else 0.9f)
            engine.setSpeechRate(if (settings.isUltron) 0.9f else 1.05f)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { finished.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { finished.complete(Unit) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { finished.complete(Unit) }
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        }
        try {
            finished.await()
        } finally {
            withContext(Dispatchers.Main) { audio.abandonAudioFocusRequest(focus) }
        }
    }

    val isSpeaking: Boolean
        get() = done?.isActive == true

    /** Cuts Jarvis off mid-sentence. Main thread. */
    fun stop() {
        tts?.stop()
        player?.runCatching { if (isPlaying) stop() }
        done?.complete(Unit)
        release()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun release() {
        player?.release()
        player = null
        audio.abandonAudioFocusRequest(focus)
    }

    /** Prefer a male en-GB voice — as close to JARVIS as stock Android gets. */
    private fun configureBritishVoice() {
        val engine = tts ?: return
        engine.language = Locale.UK
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "en" && it.locale.country == "GB" && !it.isNetworkConnectionRequired }
        val male = voices.firstOrNull { "male" in it.name.lowercase() && "female" !in it.name.lowercase() }
            ?: voices.firstOrNull { it.name.contains("-rjs-") || it.name.contains("-gbd-") } // Google's UK male voices
        (male ?: voices.firstOrNull())?.let { engine.voice = it }
    }

    private companion object {
        const val TAG = "Speaker"
    }
}
