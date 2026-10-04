package com.thumbshade.app.overlay

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Vibrations through the vibration motor itself, so they're felt even when Android's
 * "touch feedback" is off. [strength] is 0..1.
 */
object Haptics {
    enum class Kind(val ms: Long, val amplitude: Float, val predefined: Int) {
        /** Passing over an item. */
        TICK(12, 0.45f, VibrationEffect.EFFECT_TICK),
        /** Something opened. */
        OPEN(28, 0.7f, VibrationEffect.EFFECT_CLICK),
        /** You chose it. */
        CONFIRM(40, 1f, VibrationEffect.EFFECT_HEAVY_CLICK),
    }

    private fun vibrator(context: Context): Vibrator? =
        if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    fun play(context: Context, kind: Kind, strength: Float) {
        if (strength <= 0f) return
        val v = vibrator(context)?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            val effect = if (v.hasAmplitudeControl()) {
                val amp = (255 * kind.amplitude * strength).toInt().coerceIn(1, 255)
                VibrationEffect.createOneShot(kind.ms, amp)
            } else {
                VibrationEffect.createPredefined(kind.predefined)
            }
            v.vibrate(effect)
        }
    }
}
