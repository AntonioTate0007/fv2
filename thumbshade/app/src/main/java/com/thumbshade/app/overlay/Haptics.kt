package com.thumbshade.app.overlay

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Vibrations through the vibration motor itself, so they're felt even when Android's
 * "touch feedback" is off. Each moment has its own feel, so you can tell them apart without
 * looking. [strength] is 0..1.
 */
object Haptics {
    /**
     * [timings]: alternating off/on durations in ms, starting with a pause (as Android expects).
     * [levels]: strength of each segment, 0..1 (pauses are 0).
     */
    enum class Kind(val timings: LongArray, val levels: FloatArray, val fallback: Int) {
        /** Passing over an item: a light tick. */
        TICK(longArrayOf(0, 12), floatArrayOf(0f, 0.45f), VibrationEffect.EFFECT_TICK),
        /** Something opened. */
        OPEN(longArrayOf(0, 28), floatArrayOf(0f, 0.7f), VibrationEffect.EFFECT_CLICK),
        /** You chose it. */
        CONFIRM(longArrayOf(0, 40), floatArrayOf(0f, 1f), VibrationEffect.EFFECT_HEAVY_CLICK),
        /** Stage 1: the apps fold out — one short, soft tap. */
        STAGE_APPS(longArrayOf(0, 22), floatArrayOf(0f, 0.55f), VibrationEffect.EFFECT_CLICK),
        /** Stage 2: favourites fold out — "da-dum". */
        STAGE_FAVOURITES(longArrayOf(0, 25, 70, 35), floatArrayOf(0f, 0.6f, 0f, 0.9f), VibrationEffect.EFFECT_DOUBLE_CLICK),
        /** Stage 3: move mode — three taps rising into a firm buzz. */
        STAGE_MOVE(longArrayOf(0, 18, 55, 22, 55, 26, 50, 140), floatArrayOf(0f, 0.35f, 0f, 0.6f, 0f, 0.85f, 0f, 1f), VibrationEffect.EFFECT_HEAVY_CLICK),
        /** Held on an app: ready for split screen — a quick double buzz. */
        SPLIT_READY(longArrayOf(0, 45, 40, 45), floatArrayOf(0f, 0.85f, 0f, 0.85f), VibrationEffect.EFFECT_DOUBLE_CLICK),
    }

    private fun vibrator(context: Context): Vibrator? =
        if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    /** The waveform's amplitudes (1..255; 0 for pauses) at [strength]. */
    fun amplitudes(kind: Kind, strength: Float): IntArray =
        IntArray(kind.levels.size) { i -> if (kind.levels[i] <= 0f) 0 else (255 * kind.levels[i] * strength).toInt().coerceIn(1, 255) }

    fun play(context: Context, kind: Kind, strength: Float) {
        if (strength <= 0f) return
        val v = vibrator(context)?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            val effect = when {
                v.hasAmplitudeControl() -> VibrationEffect.createWaveform(kind.timings, amplitudes(kind, strength), -1)
                // No strength control: the same rhythm, on/off.
                kind.timings.size > 2 -> VibrationEffect.createWaveform(kind.timings, -1)
                else -> VibrationEffect.createPredefined(kind.fallback)
            }
            v.vibrate(effect)
        }
    }
}
