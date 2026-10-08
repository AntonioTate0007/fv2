package com.thumbshade.app.overlay

import kotlin.math.max
import kotlin.math.min

/**
 * The main colours of an app icon, for lighting in the app's own colours. Pure maths on ARGB
 * pixels (no Android), so it can be tested. Greys, near-white and near-black are ignored: they
 * would make a dull light.
 */
object IconPalette {
    private const val BINS = 18 // 20° of hue each

    /** Up to [max] colours (opaque ARGB), most prominent first; empty for a grey icon. */
    fun extract(pixels: IntArray, max: Int = 3): List<Int> {
        val weight = DoubleArray(BINS)
        val r = DoubleArray(BINS)
        val g = DoubleArray(BINS)
        val b = DoubleArray(BINS)
        for (p in pixels) {
            val a = (p ushr 24) and 0xFF
            if (a < 128) continue
            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF
            val hi = max(pr, max(pg, pb))
            val lo = min(pr, min(pg, pb))
            val v = hi / 255.0
            val s = if (hi == 0) 0.0 else (hi - lo).toDouble() / hi
            if (s < 0.25 || v < 0.2) continue
            val hue = hue(pr, pg, pb, hi, lo)
            val bin = ((hue / 360.0) * BINS).toInt().coerceIn(0, BINS - 1)
            // Vivid pixels count more than washed-out ones.
            val w = s * v
            weight[bin] += w; r[bin] += pr * w; g[bin] += pg * w; b[bin] += pb * w
        }
        val total = weight.sum()
        if (total <= 0.0) return emptyList()
        val picked = mutableListOf<Int>()
        // Take the strongest hues, skipping a bin right next to one already taken (same colour).
        for (bin in weight.indices.sortedByDescending { weight[it] }) {
            if (weight[bin] < total * 0.06 || picked.size >= max) break
            if (picked.any { prev -> circularDistance(prev, bin) <= 1 }) continue
            picked += bin
        }
        return picked.map { bin ->
            val w = weight[bin]
            (0xFF shl 24) or (kotlin.math.round(r[bin] / w).toInt().coerceIn(0, 255) shl 16) or (kotlin.math.round(g[bin] / w).toInt().coerceIn(0, 255) shl 8) or kotlin.math.round(b[bin] / w).toInt().coerceIn(0, 255)
        }
    }

    private fun circularDistance(a: Int, b: Int): Int {
        val d = kotlin.math.abs(a - b)
        return min(d, BINS - d)
    }

    private fun hue(r: Int, g: Int, b: Int, hi: Int, lo: Int): Double {
        if (hi == lo) return 0.0
        val d = (hi - lo).toDouble()
        val h = when (hi) {
            r -> ((g - b) / d) % 6
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        return if (h < 0) h + 360 else h
    }
}
