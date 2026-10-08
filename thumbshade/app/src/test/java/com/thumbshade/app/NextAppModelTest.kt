package com.thumbshade.app

import com.thumbshade.app.ai.NextAppModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NextAppModelTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun learnsWhatFollowsWhat() {
        val m = NextAppModel()
        // Every weekday morning: Gmail, then Calendar. Evenings: WhatsApp, then Photos.
        for (d in 5..9) {
            m.observe(null, "gmail", at(d, 9), zone)
            m.observe("gmail", "calendar", at(d, 9, 2), zone)
            m.observe(null, "whatsapp", at(d, 20), zone)
            m.observe("whatsapp", "photos", at(d, 20, 3), zone)
        }
        assertEquals("calendar", m.predict("gmail", at(12, 9, 5), zone, 3).first().first)
        assertEquals("photos", m.predict("whatsapp", at(12, 20, 5), zone, 3).first().first)
        // Never suggests the app you're already in.
        assertFalse(m.predict("gmail", at(12, 9, 5), zone, 5).any { it.first == "gmail" })
    }

    @Test
    fun timeOfDayMattersWithNoCurrentApp() {
        val m = NextAppModel()
        for (d in 5..9) {
            m.observe(null, "news", at(d, 7), zone)
            m.observe(null, "youtube", at(d, 22), zone)
        }
        assertEquals("news", m.predict(null, at(12, 7, 30), zone, 2).first().first)
        assertEquals("youtube", m.predict(null, at(12, 22, 30), zone, 2).first().first)
    }

    @Test
    fun freshNotificationAndHeadphonesLiftApps() {
        val m = NextAppModel()
        for (d in 5..9) m.observe("browser", "maps", at(d, 12), zone)
        val ctx = NextAppModel.Context(notificationAge = mapOf("signal" to 60_000L))
        // A strong habit still wins, but the app that just messaged you joins the suggestions.
        assertFalse(m.predict("browser", at(12, 12), zone, 2).any { it.first == "signal" })
        assertEquals(listOf("maps", "signal"), m.predict("browser", at(12, 12), zone, 2, ctx).map { it.first })
        val music = NextAppModel.Context(headphones = true, audioApps = setOf("spotify"))
        m.observe("browser", "spotify", at(9, 12, 30), zone)
        assertTrue(m.predict("browser", at(12, 12), zone, 2, music).any { it.first == "spotify" })
    }

    @Test
    fun oldHabitsFade() {
        assertEquals(5f, NextAppModel.decay(10f, 0, NextAppModel.HALF_LIFE_MS), 0.01f)
        val m = NextAppModel()
        for (i in 0 until 10) m.observe("a", "old", at(1, 10, i), zone)
        // Months later, a couple of uses of a new app outweigh ten faded ones.
        val later = at(1, 10) + 120L * 24 * 3600 * 1000
        repeat(2) { m.observe("a", "new", later + it * 60_000L, zone) }
        assertEquals("new", m.predict("a", later + 600_000L, zone, 1).first().first)
    }

    @Test
    fun survivesSaveAndLoad() {
        val m = NextAppModel()
        m.observe("x", "y", at(5, 10), zone)
        val back = NextAppModel.from(m.snapshot())
        assertEquals("y", back.predict("x", at(5, 11), zone, 1).first().first)
    }
}
