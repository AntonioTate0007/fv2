package com.jarvis.glasses

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * One-shot speech-to-text using Android's recognizer and the phone's microphone.
 * (The glasses' own mic forces a Bluetooth call-audio link that plays a chime
 * and drops speaker quality, so the phone mic is the better choice.)
 */
class Listener(private val context: Context) {

    /** Listens for one utterance. Returns the transcript, or null on silence/error. */
    suspend fun listen(): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "No speech recognizer on this phone")
            return@withContext null
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { recognizer.cancel() }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim()
                        if (cont.isActive) cont.resume(text?.ifEmpty { null })
                    }

                    override fun onError(error: Int) {
                        Log.i(TAG, "Recognizer error $error")
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                recognizer.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                        // Give people a moment to think mid-sentence before cutting off.
                        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                )
            }
        } finally {
            recognizer.destroy()
        }
    }

    private companion object {
        const val TAG = "Listener"
    }
}
