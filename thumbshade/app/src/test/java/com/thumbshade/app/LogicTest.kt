package com.thumbshade.app

import com.thumbshade.app.data.AppJson
import com.thumbshade.app.data.AppSettings
import com.thumbshade.app.data.Backup
import com.thumbshade.app.data.ThemeDef
import com.thumbshade.app.data.Themes
import com.thumbshade.app.notif.Extract
import com.thumbshade.app.rules.ActionType
import com.thumbshade.app.rules.BatchMode
import com.thumbshade.app.rules.Cat
import com.thumbshade.app.rules.Categorize
import com.thumbshade.app.rules.RuleAction
import com.thumbshade.app.rules.Schedule
import com.thumbshade.app.rules.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class LogicTest {
    private val zone = ZoneId.of("Europe/Belgrade")
    private fun at(h: Int, m: Int, day: Int = 15): Instant = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant()

    @Test
    fun batchTimes() {
        val times = listOf(17 * 60, 9 * 60)
        assertEquals(at(9, 0), Schedule.nextTime(times, at(8, 30), zone))
        assertEquals(at(17, 0), Schedule.nextTime(times, at(9, 0), zone))
        assertEquals(at(9, 0, day = 16), Schedule.nextTime(times, at(18, 0), zone))
    }

    @Test
    fun batchInterval() {
        assertEquals(at(11, 0), Schedule.nextInterval(60, at(10, 5), zone))
        assertEquals(at(10, 30), Schedule.nextInterval(30, at(10, 5), zone))
        assertEquals(at(0, 0, day = 16), Schedule.nextInterval(60, at(23, 59), zone))
        val action = RuleAction(type = ActionType.HOLD, batchMode = BatchMode.INTERVAL, intervalMinutes = 60)
        assertEquals(at(11, 0), Schedule.nextRelease(action, at(10, 5), zone))
    }

    @Test
    fun codes() {
        assertEquals("482913", Extract.code("Your verification code is 482913. Don't share it."))
        assertEquals("123456", Extract.code("G-123456 is your Google verification code."))
        assertEquals("7731", Extract.code("Use PIN 7731 to log in"))
        assertNull(Extract.code("Meeting moved to 1430 in room 2026"))
        assertNull(Extract.code("Your order 55512 has shipped"))
        assertEquals("ABC123", Extract.code("Login code: ABC-123"))
    }

    @Test
    fun linksAndPhones() {
        assertEquals(listOf("https://example.com/a?b=1"), Extract.links("See https://example.com/a?b=1."))
        assertEquals(listOf("https://www.test.org"), Extract.links("go to www.test.org"))
        assertEquals(listOf("+381 64 123 4567"), Extract.phones("Call me on +381 64 123 4567 today"))
        assertTrue(Extract.phones("Code 123456").isEmpty())
    }

    @Test
    fun categories() {
        assertEquals(Cat.EMAIL, Categorize.of("email", "x", false, false, false))
        assertEquals(Cat.MESSAGE, Categorize.of(null, "com.whatsapp", false, false, false))
        assertEquals(Cat.MESSAGE, Categorize.of(null, "x.y", true, false, false))
        assertEquals(Cat.TRANSPORT, Categorize.of(null, "x.y", false, true, false))
        assertEquals(Cat.OTHER, Categorize.of(null, "x.y", false, false, false))
        assertTrue(Categorize.isReaction("Reacted 👍 to \"see you\""))
        assertTrue(Categorize.isReaction("Liked your message"))
        assertFalse(Categorize.isReaction("I liked the film"))
    }

    @Test
    fun backupRoundTrip() {
        val rules = Templates.all.map { it.rule }
        assertTrue(rules.size >= 20)
        val backup = Backup(settings = AppSettings(buttonWidthDp = 70, pinTop = setOf("a.b")), rules = rules)
        val json = AppJson.encodeToString(Backup.serializer(), backup)
        val back = AppJson.decodeFromString(Backup.serializer(), json)
        assertEquals(backup, back)
        // Unknown keys from a newer version are ignored.
        val withExtra = json.replaceFirst("{", "{\"futureKey\":1,")
        assertEquals(backup, AppJson.decodeFromString(Backup.serializer(), withExtra))
    }

    @Test
    fun themeResolution() {
        val mine = ThemeDef(id = "mine", name = "Mine", dark = true)
        val auto = AppSettings(autoTheme = true, lightThemeId = Themes.paper.id, darkThemeId = "mine", customThemes = listOf(mine))
        assertEquals(Themes.paper, Themes.resolve(auto, systemDark = false))
        assertEquals(mine, Themes.resolve(auto, systemDark = true))
        val fixed = auto.copy(autoTheme = false, activeThemeId = Themes.ocean.id)
        assertEquals(Themes.ocean, Themes.resolve(fixed, systemDark = false))
        // A deleted or unknown theme falls back to a built-in one.
        assertEquals(Themes.dark, Themes.resolve(fixed.copy(activeThemeId = "gone"), systemDark = false))
        assertEquals(Themes.light, Themes.resolve(auto.copy(lightThemeId = "gone"), systemDark = false))
    }
}
