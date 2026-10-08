package com.thumbshade.app

import com.thumbshade.app.data.GestureAction
import com.thumbshade.app.data.GestureMode
import com.thumbshade.app.data.GestureType
import com.thumbshade.app.overlay.ActionWheel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionWheelTest {
    private fun mode(vararg filled: Int) = GestureMode(
        wheel = true,
        wheelSlots = List(GestureMode.SLOT_COUNT) { if (it in filled) GestureAction(GestureType.HOME) else GestureAction() },
    )

    @Test
    fun emptySlotsAreSkipped() {
        val slots = ActionWheel.layout(mode(0, 3, 6, 20), 500f, 1000f, 50f, 50f, 1f, 1080f, 2200f)
        assertEquals(4, slots.size)
        assertEquals(listOf(0, 0, 1, 2), slots.map { it.ring })
    }

    @Test
    fun firstInnerSlotSitsAboveTheButton() {
        val s = ActionWheel.layout(mode(0), 500f, 1000f, 50f, 50f, 1f, 1080f, 2200f).single()
        assertEquals(500f, s.x, 0.5f)
        assertEquals(1000f - 50f - 56f, s.y, 0.5f)
    }

    @Test
    fun slotsOffTheRightEdgeAreMirroredLeft() {
        // Button docked at the right edge: the inner ring's right-hand slots would be off screen.
        val slots = ActionWheel.layout(mode(*IntArray(6) { it }), 1050f, 1000f, 30f, 30f, 1f, 1080f, 2200f)
        assertTrue(slots.all { it.x in 30f..1050f })
        assertTrue(slots.any { it.x < 1050f - 60f })
    }
}
