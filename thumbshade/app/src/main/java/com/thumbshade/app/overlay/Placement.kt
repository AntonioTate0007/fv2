package com.thumbshade.app.overlay

import com.thumbshade.app.data.LandscapeBehavior
import com.thumbshade.app.data.SnapStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the floating button goes. Pure arithmetic so it can be unit-tested. */
object Placement {
    data class Pos(val x: Int, val y: Int)

    /** x of a docked button of width [w] on a screen [screenW] wide. */
    fun dockX(screenW: Int, w: Int, right: Boolean, style: SnapStyle): Int = when (style) {
        SnapStyle.HALF -> if (right) screenW - w / 2 else -w / 2
        SnapStyle.FULL -> if (right) screenW - w else 0
    }

    /**
     * After a drag ends: null = stay free where it was dropped, true = dock right, false = dock left.
     * A fast enough fling sideways docks on that side wherever it's released; otherwise it docks
     * only when released within [zoneFrac] of an edge.
     */
    fun releaseDock(x: Int, w: Int, screenW: Int, vxPx: Float, flingPx: Float, snap: Boolean, zoneFrac: Float): Boolean? {
        if (flingPx > 0f && abs(vxPx) >= flingPx) return vxPx > 0
        if (!snap) return null
        val center = x + w / 2f
        return when {
            center <= screenW * zoneFrac -> false
            center >= screenW * (1f - zoneFrac) -> true
            else -> null
        }
    }

    /** Keep the button on screen unless off-screen placement is allowed (then keep a quarter visible). */
    fun clamp(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int, allowOffscreen: Boolean): Pos {
        return if (allowOffscreen) {
            Pos(x.coerceIn(-w * 3 / 4, screenW - w / 4), y.coerceIn(-h * 3 / 4, screenH - h / 4))
        } else {
            Pos(x.coerceIn(0, max(0, screenW - w)), y.coerceIn(0, max(0, screenH - h)))
        }
    }

    /** Store positions as fractions of the portrait screen, so they survive rotation and size changes. */
    fun toFrac(px: Int, size: Int, extent: Int): Float {
        val room = max(1, extent - size)
        return (px.toFloat() / room)
    }

    /**
     * Turn a saved fraction back into pixels on the current screen. In landscape, RELATIVE maps the
     * fraction onto the landscape screen; KEEP uses the portrait coordinate and clamps it.
     */
    fun fromFrac(frac: Float, size: Int, extentNow: Int, extentPortrait: Int, landscape: Boolean, behavior: LandscapeBehavior): Int {
        val useExtent = if (landscape && behavior == LandscapeBehavior.KEEP) extentPortrait else extentNow
        val px = (frac * max(1, useExtent - size)).roundToInt()
        return min(px, max(0, extentNow - size))
    }
}
