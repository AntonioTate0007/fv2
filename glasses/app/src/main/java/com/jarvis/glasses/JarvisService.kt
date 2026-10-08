package com.jarvis.glasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Jarvis's brain. Runs in the foreground so it keeps listening with the screen off:
 *
 *   "Jarvis" (wake word) / glasses tap / notification button
 *     -> listen to your question on the phone mic, while the glasses camera grabs the sharpest photo
 *     -> ask Grok (question + photo + recent conversation)
 *     -> speak the answer in Grok's voice through the glasses' speakers
 *     -> briefly listen for a follow-up, then go back to waiting for "Jarvis"
 */
class JarvisService : Service() {

    enum class Status(val label: String) {
        OFF("Off"),
        WAITING("Waiting for \"Jarvis\""),
        LISTENING("Listening…"),
        THINKING("Thinking…"),
        SPEAKING("Speaking"),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: Settings
    private lateinit var grok: GrokClient
    private lateinit var speaker: Speaker
    private lateinit var listener: Listener
    private lateinit var wake: WakeWord
    private lateinit var glasses: GlassesLink
    private var mediaSession: MediaSession? = null
    private var conversation: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        grok = GrokClient(settings)
        speaker = Speaker(this, settings)
        listener = Listener(this)
        glasses = GlassesLink(scope)
        wake = WakeWord(this) { summon() }

        createChannel()
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(Status.WAITING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }
        if (started.isFailure) {
            // Android only lets a mic service start while the app is on screen.
            Log.w(TAG, "Couldn't start in the foreground; open the app to start Jarvis", started.exceptionOrNull())
            stopSelf()
            return
        }

        reloadWakeWord()
        setupTouchpadTrigger()
        scope.launch { glasses.summons.collect { summon() } }
        // Connect to the glasses up front so the first photo is quick.
        scope.launch { glasses.ensureSession() }
        setStatus(Status.WAITING)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TALK -> summon()
            ACTION_HUSH -> hush()
            ACTION_RELOAD -> reloadWakeWord()
            ACTION_QUIT -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        conversation?.cancel()
        wake.release()
        speaker.shutdown()
        glasses.close()
        mediaSession?.release()
        scope.cancel()
        setStatus(Status.OFF)
        super.onDestroy()
    }

    /** Start (or restart, interrupting Jarvis) a conversation turn. */
    private fun summon() {
        if (speaker.isSpeaking) speaker.stop()
        val previous = conversation
        conversation = scope.launch {
            // Let the interrupted turn finish its cleanup before this one grabs the mic.
            previous?.cancelAndJoin()
            converse()
        }
    }

    private fun hush() {
        conversation?.cancel()
        speaker.stop()
        backToWaiting()
    }

    private suspend fun converse() {
        wake.pause()
        try {
            if (settings.xaiKey.isBlank()) {
                say("I'm afraid I need an xAI API key before I can think, ${settings.honorific}. Please add one in the app.", useGrokVoice = false)
                return
            }
            var turns = 0
            do {
                val heard = listenWithPhoto() ?: break
                turns++
                val question = heard.first.cleanQuestion()
                if (question.isEmpty() || question.isDismissal()) break
                log("You: $question")

                setStatus(Status.THINKING)
                val answer = try {
                    grok.ask(question, heard.second)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Grok failed", e)
                    log("Error: ${e.message}")
                    say("Apologies, ${settings.honorific}, I couldn't reach Grok. ${e.message ?: ""}".take(220), useGrokVoice = false)
                    break
                }
                log("Jarvis: $answer")
                say(answer, useGrokVoice = settings.grokVoice)
            } while (settings.followUps && turns < MAX_FOLLOW_UPS)
        } finally {
            backToWaiting()
        }
    }

    /** Listens on the phone mic while (in parallel) the glasses camera takes photos. */
    private suspend fun listenWithPhoto(): Pair<String, ByteArray?>? = coroutineScope {
        setStatus(Status.LISTENING)
        val stillTalking = AtomicBoolean(true)
        val photo = if (settings.useCamera && glasses.isRegistered) {
            async { runCatching { glasses.captureSharpest { stillTalking.get() } }.getOrNull() }
        } else null

        val text = listener.listen()
        stillTalking.set(false)
        if (text.isNullOrBlank()) {
            photo?.cancel()
            return@coroutineScope null
        }
        val jpeg = photo?.let { withTimeoutOrNull(PHOTO_WAIT_MS) { it.await() } }
        photo?.cancel()
        text to jpeg
    }

    private suspend fun say(text: String, useGrokVoice: Boolean) {
        setStatus(Status.SPEAKING)
        // Let "Jarvis" interrupt a long answer.
        wake.resume()
        try {
            if (useGrokVoice) {
                val mp3 = runCatching { grok.speak(text) }
                    .onFailure { Log.w(TAG, "Grok TTS failed, using phone voice", it) }
                    .getOrNull()
                if (mp3 != null) {
                    speaker.playMp3(mp3)
                    return
                }
            }
            speaker.sayLocally(text)
        } finally {
            wake.pause()
        }
    }

    private fun backToWaiting() {
        wake.resume()
        setStatus(Status.WAITING)
    }

    private fun reloadWakeWord() {
        wake.init(settings.picovoiceKey)
        wake.problem?.let { log(it) }
        if (conversation?.isActive != true) wake.resume()
    }

    /**
     * Tapping the glasses' touchpad sends a play/pause media button to the phone.
     * While no music app is playing, that press comes to Jarvis instead.
     */
    private fun setupTouchpadTrigger() {
        mediaSession = MediaSession(this, "Jarvis").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        ?: return false
                    if (event.keyCode !in TOUCHPAD_KEYS) return false
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        if (speaker.isSpeaking) hush() else summon()
                    }
                    return true
                }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PAUSED, 0, 1f)
                    .build()
            )
            isActive = true
        }
    }

    private fun setStatus(s: Status) {
        _status.value = s
        if (s != Status.OFF) {
            getSystemService(NotificationManager::class.java)!!.notify(NOTIFICATION_ID, buildNotification(s))
        }
    }

    private fun log(line: String) {
        _transcript.value = (_transcript.value + line).takeLast(40)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)!!.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows that Jarvis is listening for you" }
        )
    }

    private fun buildNotification(s: Status): Notification {
        fun action(act: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, JarvisService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis")
            .setContentText(s.label)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Talk", action(ACTION_TALK, 1))
            .addAction(0, "Hush", action(ACTION_HUSH, 2))
            .addAction(0, "Quit", action(ACTION_QUIT, 3))
            .build()
    }

    private fun String.cleanQuestion(): String =
        trim().replace(Regex("^(hey |ok |okay )?(jarvis|ultron)[,.!]?\\s*", RegexOption.IGNORE_CASE), "").trim()

    private fun String.isDismissal(): Boolean =
        lowercase().trim(' ', '.', '!').let { it in DISMISSALS }

    companion object {
        private const val TAG = "Jarvis"
        private const val CHANNEL = "jarvis"
        private const val NOTIFICATION_ID = 7
        private const val MAX_FOLLOW_UPS = 6
        private const val PHOTO_WAIT_MS = 6_000L

        const val ACTION_TALK = "com.jarvis.glasses.TALK"
        const val ACTION_HUSH = "com.jarvis.glasses.HUSH"
        const val ACTION_QUIT = "com.jarvis.glasses.QUIT"
        const val ACTION_RELOAD = "com.jarvis.glasses.RELOAD"

        private val TOUCHPAD_KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
        private val DISMISSALS = setOf(
            "stop", "cancel", "never mind", "nevermind", "that's all", "that is all",
            "nothing", "go to sleep", "goodbye", "bye",
        )

        private val _status = MutableStateFlow(Status.OFF)
        val status: StateFlow<Status> = _status
        private val _transcript = MutableStateFlow<List<String>>(emptyList())
        val transcript: StateFlow<List<String>> = _transcript

        fun send(context: Context, action: String? = null) {
            val intent = Intent(context, JarvisService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
