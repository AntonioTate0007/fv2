package com.thumbshade.app

import com.thumbshade.app.ai.Engagement
import com.thumbshade.app.ai.Entities
import com.thumbshade.app.ai.Replies
import com.thumbshade.app.ai.Summaries
import com.thumbshade.app.ai.Urgency
import com.thumbshade.app.ai.UrgencyModel
import com.thumbshade.app.rules.Cat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AiTest {
    private val zone = ZoneId.of("America/New_York")
    // Saturday 3 October 2026, 2:30 pm.
    private val now = ZonedDateTime.of(2026, 10, 3, 14, 30, 0, 0, zone)

    @Test
    fun urgency() {
        fun f(cat: Cat, text: String, contact: Boolean = false, imp: Int = 3, group: Boolean = false) =
            UrgencyModel.classify(UrgencyModel.Features(cat, imp, text, fromContact = contact, isGroupChat = group))
        assertEquals(Urgency.URGENT, f(Cat.MISSED_CALL, "Missed call from Mom"))
        assertEquals(Urgency.URGENT, f(Cat.MESSAGE, "Call me now, it's urgent"))
        assertEquals(Urgency.LOW, f(Cat.EMAIL, "Flash sale: 40% off everything this weekend"))
        assertEquals(Urgency.LOW, f(Cat.SOCIAL, "Alex liked your photo"))
        assertEquals(Urgency.PERSONAL, f(Cat.MESSAGE, "Are we still on for tonight?"))
        assertEquals(Urgency.NORMAL, f(Cat.MESSAGE, "lol", group = true))
        assertEquals(Urgency.PERSONAL, f(Cat.EMAIL, "Dinner Sunday?", contact = true))
        assertEquals(Urgency.LOW, f(Cat.OTHER, "Your weekly summary", imp = 2))
        // Learning moves it one step, never past personal and never out of urgent.
        val promo = UrgencyModel.Features(Cat.EMAIL, 3, "New arrivals: shop now")
        assertEquals(Urgency.NORMAL, UrgencyModel.classify(promo, learned = 0.8f))
        val normal = UrgencyModel.Features(Cat.OTHER, 3, "Backup finished")
        assertEquals(Urgency.LOW, UrgencyModel.classify(normal, learned = -0.9f))
    }

    @Test
    fun engagementDecays() {
        assertEquals(10f, Engagement.decay(10f, 0, 0), 0.001f)
        assertEquals(5f, Engagement.decay(10f, 0, Engagement.HALF_LIFE_MS), 0.01f)
        assertTrue(Engagement.squash(100f) < 1.0001f && Engagement.squash(100f) > 0.99f)
        assertEquals(0f, Engagement.squash(0f), 0f)
    }

    @Test
    fun summaries() {
        val lines = listOf(
            Summaries.Line("Sarah", "Dinner tonight?"),
            Summaries.Line("Tom", "Yes! Where for dinner?"),
            Summaries.Line("Ana", "Pizza place on Main"),
            Summaries.Line("Ben", "Dinner at 8 works"),
            Summaries.Line("Sarah", "See you there"),
        )
        val s = Summaries.conversation(lines, "tonight 8 pm")
        assertTrue(s, s.startsWith("Sarah, Ben and 2 others · 5 messages · about dinner"))
        assertTrue(s, s.endsWith("latest: “See you there”"))
        assertEquals("Ana and Ben", Summaries.names(listOf("Ana", "", "Ben", "Ana")))
        val g = Summaries.group("Gmail", listOf(Summaries.Brief("Amazon", "Your order shipped"), Summaries.Brief("Chase", "Statement ready"), Summaries.Brief("Amazon", "Deal")))
        assertEquals("3 from Gmail: Amazon (2), Chase · latest: “Your order shipped”", g)
    }

    @Test
    fun replies() {
        val where = Replies.suggest("Where are you??")
        assertEquals(Replies.Kind.LOCATION, where.first().kind)
        assertTrue(where.any { it.text == "Be there in 5" })
        assertEquals("You're welcome!", Replies.suggest("thanks so much").first().text)
        assertEquals(listOf("Yes", "No", "Not sure"), Replies.suggest("Can you pick up milk?").map { it.text })
        // Android's own suggestions come first, without duplicates.
        val merged = Replies.suggest("Can you pick up milk?", listOf("Sure", "yes"))
        assertEquals(listOf("Sure", "yes", "No", "Not sure"), merged.map { it.text })
    }

    @Test
    fun events() {
        val flight = Entities.event("Your flight boards tomorrow at 10 AM", now)!!
        assertEquals(ZonedDateTime.of(2026, 10, 4, 10, 0, 0, 0, zone), flight.start)
        assertFalse(flight.allDay)
        val dinner = Entities.event("Dinner tonight at 8?", now)!!
        assertEquals(20, dinner.start.hour)
        assertEquals(3, dinner.start.dayOfMonth)
        val appt = Entities.event("Appointment on Oct 12 at 3:30pm", now)!!
        assertEquals(ZonedDateTime.of(2026, 10, 12, 15, 30, 0, 0, zone), appt.start)
        val monday = Entities.event("Meeting next Monday 9:00", now)!!
        assertEquals(5, monday.start.dayOfMonth)
        assertEquals(9, monday.start.hour)
        val allDay = Entities.event("Rent is due 11/1", now)!!
        assertTrue(allDay.allDay)
        assertEquals(11, allDay.start.monthValue)
        // Past date without a year rolls to next year.
        assertEquals(2027, Entities.event("Party on March 3", now)!!.start.year)
        assertNull(Entities.event("Sent 10:42", now))
        assertNull(Entities.event("lol ok", now))
    }

    @Test
    fun otherEntities() {
        assertEquals("UPS", Entities.tracking("Your package 1Z999AA10123456784 is on its way")!!.carrier)
        assertEquals("FedEx", Entities.tracking("FedEx shipment 123456789012 out for delivery")!!.carrier)
        assertNull(Entities.tracking("Call 123456789012"))
        assertEquals("AA123", Entities.flight("Flight AA 123 departs at gate B12")!!.code)
        assertNull(Entities.flight("Room AA123"))
        assertEquals("1600 Amphitheatre Parkway, Mountain View", Entities.address("Meet at 1600 Amphitheatre Parkway, Mountain View tomorrow")!!.text)
        assertNotNull(Entities.find("Your flight UA 456 boards tomorrow at 10 AM", now).firstOrNull { it is Entities.Event })
    }
}
