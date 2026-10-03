package com.thumbshade.app

import com.thumbshade.app.data.TickSound
import com.thumbshade.app.overlay.ScrollSounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScrollSoundsTest {
    @Test
    fun everySoundIsShortAndInRange() {
        TickSound.entries.filter { it != TickSound.NONE && it != TickSound.SYSTEM }.forEach { s ->
            val pcm = ScrollSounds.synth(s)
            assertTrue("$s is empty", pcm.isNotEmpty())
            assertTrue("$s is too long", pcm.size <= 44_100 / 5)
            assertTrue("$s clips", pcm.all { abs(it) <= 1.5f })
            assertTrue("$s is silent", pcm.any { abs(it) > 0.05f })
            assertEquals("$s should end silent", 0f, pcm.last(), 0.001f)
        }
        assertTrue(ScrollSounds.synth(TickSound.NONE).isEmpty())
    }
}
