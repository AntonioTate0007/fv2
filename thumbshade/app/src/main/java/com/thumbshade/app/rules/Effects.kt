package com.thumbshade.app.rules

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

/** Sounds, vibration, torch, ringer and speech for rule actions and button gestures. */
object Effects {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = mutableListOf<String>()
    var torchOn = false
        private set

    fun toast(context: Context, text: String) {
        main.post { Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    fun alert(context: Context, soundUri: String, vibe: Vibe) {
        val uri: Uri = soundUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        runCatching {
            RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }?.play()
        }
        vibrate(context, vibe.pattern)
    }

    fun vibrate(context: Context, pattern: LongArray) {
        if (pattern.isEmpty()) return
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun torchCamera(cm: CameraManager): String? = cm.cameraIdList.firstOrNull { id ->
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }

    fun setTorch(context: Context, on: Boolean) {
        val cm = context.getSystemService(CameraManager::class.java) ?: return
        runCatching {
            val id = torchCamera(cm) ?: return
            cm.setTorchMode(id, on)
            torchOn = on
        }
    }

    fun toggleTorch(context: Context) = setTorch(context, !torchOn)

    fun flashTorch(context: Context, times: Int) {
        val n = times.coerceIn(1, 20)
        for (i in 0 until n) {
            main.postDelayed({ setTorch(context, true) }, i * 400L)
            main.postDelayed({ setTorch(context, false) }, i * 400L + 200L)
        }
    }

    fun setRinger(context: Context, choice: RingerChoice) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            am.ringerMode = when (choice) {
                RingerChoice.NORMAL -> AudioManager.RINGER_MODE_NORMAL
                RingerChoice.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                RingerChoice.SILENT -> AudioManager.RINGER_MODE_SILENT
            }
        }.onFailure { toast(context, "Allow Do Not Disturb access to change the ringer") }
    }

    /** Mute toggles between the user's ringer and vibrate. */
    fun toggleMute(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        setRinger(context, if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) RingerChoice.VIBRATE else RingerChoice.NORMAL)
    }

    @Synchronized
    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        val engine = tts
        if (engine != null && ttsReady) {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "thumbshade-" + System.nanoTime())
            return
        }
        pendingSpeech += text
        if (engine == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (ttsReady) {
                        tts?.language = Locale.getDefault()
                        pendingSpeech.forEach { tts?.speak(it, TextToSpeech.QUEUE_ADD, null, "thumbshade-" + System.nanoTime()) }
                    }
                    pendingSpeech.clear()
                }
            }
        }
    }
}

class RulesAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Reminders.ACTION -> Reminders.fire(context, intent)
        }
    }
}

/** Quick-settings tile: shows how many notifications are held and hands them all back. */
class ReleaseTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val n = HoldStore.releaseAll()
        Effects.toast(this, if (n == 0) "Nothing is being held" else "Released $n notification(s)")
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val n = HoldStore.count
        tile.state = if (n > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (n > 0) "Release $n held" else "Nothing held"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = "ThumbShade"
        tile.icon = Icon.createWithResource(this, com.thumbshade.app.R.drawable.ic_tile_release)
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, ReleaseTileService::class.java)) }
        }
    }
}
