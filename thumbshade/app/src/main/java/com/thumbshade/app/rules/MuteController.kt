package com.thumbshade.app.rules

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.app.NotificationManager
import android.provider.Settings
import com.thumbshade.app.notif.RoundTrips
import com.thumbshade.app.notif.ShadeItem
import com.thumbshade.app.notif.ShadeListenerService

/**
 * A listener cannot make someone else's notification silent. What it can do:
 *  1. ask the system to suppress notification sound and vibration (a listener hint) for as long
 *     as the notification's sound lasts, and
 *  2. snooze the notification for a blink, which cuts off a sound that has already started.
 *     It comes straight back, quietly; RoundTrips keeps the shade from flickering.
 * Windows extend while more muted notifications arrive, up to a cap, then cool down so a flood of
 * notifications can't keep the phone silent indefinitely.
 */
object MuteController {
    private const val BLINK_MS = 300L
    private const val MAX_WINDOW_MS = 15_000L
    private const val COOLDOWN_MS = 30_000L
    private const val MIN_SOUND_MS = 1_000L
    private const val MAX_SOUND_MS = 7_000L
    private const val FALLBACK_SOUND_MS = 3_000L

    private val main = Handler(Looper.getMainLooper())
    private var windowStart = 0L
    private var windowEnd = 0L
    private var cooldownUntil = 0L
    private var active = false
    private val blinkCount = HashMap<String, Int>()

    private val closer = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now < windowEnd) {
                main.postDelayed(this, windowEnd - now)
                return
            }
            if (windowEnd - windowStart >= MAX_WINDOW_MS) cooldownUntil = now + COOLDOWN_MS
            active = false
            ShadeListenerService.suppressEffects(false)
        }
    }

    @Synchronized
    fun mute(context: Context, item: ShadeItem): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!active && now < cooldownUntil) return false
        val length = soundLengthMs(context, item)
        if (!active) {
            active = true
            windowStart = now
            windowEnd = now + length
            ShadeListenerService.suppressEffects(true)
            main.postDelayed(closer, length)
        } else {
            windowEnd = minOf(windowStart + MAX_WINDOW_MS, maxOf(windowEnd, now + length))
        }
        blink(item.key)
        return true
    }

    private fun blink(key: String) {
        if (Build.VERSION.SDK_INT < 30) return
        // A notification that keeps getting re-posted would otherwise blink forever.
        val n = (blinkCount[key] ?: 0) + 1
        blinkCount[key] = n
        if (blinkCount.size > 500) blinkCount.clear()
        if (n > 3) return
        RoundTrips.expect(key, BLINK_MS + 3_000)
        if (!ShadeListenerService.snooze(key, BLINK_MS)) RoundTrips.forget(key)
    }

    /** How long this notification's sound plays, so the mute lasts exactly that long. */
    private fun soundLengthMs(context: Context, item: ShadeItem): Long {
        val uri: Uri? = runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            // Our own process can't read other apps' channels; fall back to the notification's sound or the default.
            @Suppress("DEPRECATION")
            item.sbn.notification.sound ?: nm?.getNotificationChannel(item.channelId)?.sound
        }.getOrNull() ?: Settings.System.DEFAULT_NOTIFICATION_URI
        val ms = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                r.release()
            }
        }.getOrNull() ?: FALLBACK_SOUND_MS
        return ms.coerceIn(MIN_SOUND_MS, MAX_SOUND_MS) + 300
    }
}
