package com.thumbshade.app

import com.thumbshade.app.overlay.IconPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconPaletteTest {
    private fun px(argb: Long, n: Int) = IntArray(n) { argb.toInt() }

    @Test
    fun picksTheMainColoursBiggestFirst() {
        // A Gmail-like icon: mostly red, some blue, a little yellow, a white background, transparent corners.
        val pixels = px(0xFFEA4335, 500) + px(0xFF4285F4, 300) + px(0xFFFBBC05, 120) + px(0xFFFFFFFF, 400) + px(0x00000000, 300)
        val colors = IconPalette.extract(pixels, 4)
        assertEquals(3, colors.size)
        assertEquals(0xFFEA4335.toInt(), colors[0])
        assertEquals(0xFF4285F4.toInt(), colors[1])
        assertEquals(0xFFFBBC05.toInt(), colors[2])
    }

    @Test
    fun greyIconsGiveNothing() {
        val pixels = px(0xFF808080, 400) + px(0xFFFFFFFF, 400) + px(0xFF101010, 400)
        assertTrue(IconPalette.extract(pixels).isEmpty())
    }

    @Test
    fun similarShadesCountOnce() {
        // Two greens a few degrees apart are one colour, not two.
        val pixels = px(0xFF25D366, 400) + px(0xFF20C060, 300)
        assertEquals(1, IconPalette.extract(pixels).size)
    }
}
