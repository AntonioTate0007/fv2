package com.thumbshade.app

import com.thumbshade.app.data.LandscapeBehavior
import com.thumbshade.app.data.SnapStyle
import com.thumbshade.app.overlay.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlacementTest {
    @Test
    fun dockPositions() {
        assertEquals(-50, Placement.dockX(1000, 100, right = false, SnapStyle.HALF))
        assertEquals(950, Placement.dockX(1000, 100, right = true, SnapStyle.HALF))
        assertEquals(0, Placement.dockX(1000, 100, right = false, SnapStyle.FULL))
        assertEquals(900, Placement.dockX(1000, 100, right = true, SnapStyle.FULL))
    }

    @Test
    fun releaseNearEdgeOrFling() {
        // Dropped near the left edge docks left, near the right docks right, middle stays free.
        assertEquals(false, Placement.releaseDock(50, 100, 1000, 0f, 2000f, snap = true, zoneFrac = 0.25f))
        assertEquals(true, Placement.releaseDock(850, 100, 1000, 0f, 2000f, snap = true, zoneFrac = 0.25f))
        assertNull(Placement.releaseDock(450, 100, 1000, 0f, 2000f, snap = true, zoneFrac = 0.25f))
        // A hard fling docks on its side even from the middle, and even with snapping off.
        assertEquals(true, Placement.releaseDock(450, 100, 1000, 2500f, 2000f, snap = false, zoneFrac = 0.25f))
        assertEquals(false, Placement.releaseDock(450, 100, 1000, -2500f, 2000f, snap = true, zoneFrac = 0.25f))
        // Fling off (0) and snapping off: always free.
        assertNull(Placement.releaseDock(10, 100, 1000, 9000f, 0f, snap = false, zoneFrac = 0.25f))
    }

    @Test
    fun clampAndFractions() {
        assertEquals(Placement.Pos(0, 1900), Placement.clamp(-30, 2500, 100, 100, 1000, 2000, allowOffscreen = false))
        assertEquals(Placement.Pos(-30, 1975), Placement.clamp(-30, 2500, 100, 100, 1000, 2000, allowOffscreen = true))
        val frac = Placement.toFrac(900, 100, 2000) // 900 of 1900 room
        // Portrait round trip.
        assertEquals(900, Placement.fromFrac(frac, 100, 2000, 2000, landscape = false, LandscapeBehavior.RELATIVE))
        // Landscape screen 1000 tall: relative scales, keep clamps.
        assertEquals(426, Placement.fromFrac(frac, 100, 1000, 2000, landscape = true, LandscapeBehavior.RELATIVE))
        assertEquals(900, Placement.fromFrac(frac, 100, 1000, 2000, landscape = true, LandscapeBehavior.KEEP))
        assertEquals(900, Placement.fromFrac(1f, 100, 1000, 2000, landscape = true, LandscapeBehavior.KEEP))
    }
}
