package com.thumbshade.app

import com.thumbshade.app.overlay.Haptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsTest {
    @Test
    fun patternsAreWellFormed() {
        Haptics.Kind.entries.forEach { k ->
            assertEquals("$k timings and levels differ in length", k.timings.size, k.levels.size)
            assertEquals("$k must start with a pause", 0f, k.levels[0], 0f)
            val amps = Haptics.amplitudes(k, 1f)
            k.levels.forEachIndexed { i, l -> if (l > 0f) assertTrue(amps[i] in 1..255) else assertEquals(0, amps[i]) }
        }
    }

    @Test
    fun stagesFeelDifferent() {
        val stages = listOf(Haptics.Kind.STAGE_APPS, Haptics.Kind.STAGE_FAVOURITES, Haptics.Kind.STAGE_MOVE, Haptics.Kind.SPLIT_READY)
        // Each has a different number of pulses or a different rhythm.
        for (a in stages) for (b in stages) if (a != b) assertNotEquals("$a vs $b", a.timings.toList(), b.timings.toList())
        // Pulses: one, two, four, two.
        assertEquals(listOf(1, 2, 4, 2), stages.map { k -> k.levels.count { it > 0f } })
    }

    @Test
    fun strengthScalesButNeverSilences() {
        val weak = Haptics.amplitudes(Haptics.Kind.STAGE_MOVE, 0.1f)
        assertTrue(weak.filterIndexed { i, _ -> i % 2 == 1 }.all { it >= 1 })
    }
}
