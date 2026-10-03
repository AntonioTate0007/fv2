package com.thumbshade.app

import com.thumbshade.app.rules.Cat
import com.thumbshade.app.rules.Cond
import com.thumbshade.app.rules.CondGroup
import com.thumbshade.app.rules.CondType
import com.thumbshade.app.rules.DeviceAspect
import com.thumbshade.app.rules.DeviceSnapshot
import com.thumbshade.app.rules.Flag
import com.thumbshade.app.rules.NotifFacts
import com.thumbshade.app.rules.Rule
import com.thumbshade.app.rules.RuleMatcher
import com.thumbshade.app.rules.TextField
import com.thumbshade.app.rules.WordMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleMatcherTest {
    private fun facts(
        title: String = "Alice",
        text: String = "Your code is 123456",
        pkg: String = "com.example.chat",
        category: Cat = Cat.MESSAGE,
        importance: Int = 4,
        groupChat: Boolean = false,
    ) = NotifFacts(
        key = "k", pkg = pkg, appName = "Chat", title = title, text = text, category = category,
        importance = importance, isGroupChat = groupChat, fromContact = false, hasPicture = false,
        hasReply = true, ongoing = false, silent = false, isReaction = false,
    )

    private val device = DeviceSnapshot(screenOn = true, dayOfWeek = 3, minuteOfDay = 23 * 60)

    @Test
    fun emptyRuleMatchesEverything() {
        assertTrue(RuleMatcher.matches(Rule(), facts(), device))
    }

    @Test
    fun appScopeAndExclusions() {
        assertFalse(RuleMatcher.matches(Rule(apps = setOf("other.app")), facts(), device))
        assertTrue(RuleMatcher.matches(Rule(apps = setOf("com.example.chat")), facts(), device))
        assertFalse(RuleMatcher.matches(Rule(excludeApps = setOf("com.example.chat")), facts(), device))
        assertFalse(RuleMatcher.matches(Rule(enabled = false), facts(), device))
    }

    @Test
    fun textModes() {
        val contains = Cond(type = CondType.TEXT, words = "pizza| code ")
        assertTrue(RuleMatcher.evalCond(contains, facts(), device))
        val whole = Cond(type = CondType.TEXT, words = "cod", wordMode = WordMode.WHOLE_WORD)
        assertFalse(RuleMatcher.evalCond(whole, facts(), device))
        val whole2 = Cond(type = CondType.TEXT, words = "CODE", wordMode = WordMode.WHOLE_WORD)
        assertTrue(RuleMatcher.evalCond(whole2, facts(), device))
        val regex = Cond(type = CondType.TEXT, words = "\\b\\d{6}\\b", wordMode = WordMode.REGEX)
        assertTrue(RuleMatcher.evalCond(regex, facts(), device))
        val badRegex = Cond(type = CondType.TEXT, words = "([", wordMode = WordMode.REGEX)
        assertFalse(RuleMatcher.evalCond(badRegex, facts(), device))
        val titleOnly = Cond(type = CondType.TEXT, words = "code", field = TextField.TITLE)
        assertFalse(RuleMatcher.evalCond(titleOnly, facts(), device))
    }

    @Test
    fun negationAndGroups() {
        val isMessage = Cond(type = CondType.CATEGORY, categories = setOf(Cat.MESSAGE))
        val isGroup = Cond(type = CondType.FLAG, flag = Flag.GROUP_CHAT)
        val all = CondGroup(matchAll = true, conditions = listOf(isMessage, isGroup))
        val any = CondGroup(matchAll = false, conditions = listOf(isMessage, isGroup))
        assertFalse(RuleMatcher.evalGroup(all, facts(), device))
        assertTrue(RuleMatcher.evalGroup(any, facts(), device))
        assertTrue(RuleMatcher.evalGroup(all.copy(negate = true), facts(), device))
        assertTrue(RuleMatcher.evalCond(isGroup.copy(negate = true), facts(), device))
        val nested = CondGroup(conditions = listOf(isMessage), groups = listOf(CondGroup(matchAll = false, conditions = listOf(isGroup))))
        assertTrue(RuleMatcher.evalGroup(nested, facts(groupChat = true), device))
        assertFalse(RuleMatcher.evalGroup(nested, facts(groupChat = false), device))
    }

    @Test
    fun timeWindowWrapsPastMidnight() {
        val night = Cond(type = CondType.TIME, startMinute = 22 * 60, endMinute = 7 * 60, days = setOf(3))
        assertTrue(RuleMatcher.evalCond(night, facts(), DeviceSnapshot(dayOfWeek = 3, minuteOfDay = 23 * 60)))
        // 02:00 on Thursday belongs to Wednesday night.
        assertTrue(RuleMatcher.evalCond(night, facts(), DeviceSnapshot(dayOfWeek = 4, minuteOfDay = 2 * 60)))
        assertFalse(RuleMatcher.evalCond(night, facts(), DeviceSnapshot(dayOfWeek = 3, minuteOfDay = 2 * 60)))
        assertFalse(RuleMatcher.evalCond(night, facts(), DeviceSnapshot(dayOfWeek = 3, minuteOfDay = 12 * 60)))
        val work = Cond(type = CondType.TIME, startMinute = 9 * 60, endMinute = 17 * 60, days = setOf(1, 2, 3, 4, 5))
        assertTrue(RuleMatcher.evalCond(work, facts(), DeviceSnapshot(dayOfWeek = 5, minuteOfDay = 9 * 60)))
        assertFalse(RuleMatcher.evalCond(work, facts(), DeviceSnapshot(dayOfWeek = 5, minuteOfDay = 17 * 60)))
        assertFalse(RuleMatcher.evalCond(work, facts(), DeviceSnapshot(dayOfWeek = 6, minuteOfDay = 10 * 60)))
    }

    @Test
    fun importanceLengthAndDevice() {
        assertTrue(RuleMatcher.evalCond(Cond(type = CondType.IMPORTANCE, minImportance = 4, maxImportance = 5), facts(), device))
        assertFalse(RuleMatcher.evalCond(Cond(type = CondType.IMPORTANCE, minImportance = 0, maxImportance = 2), facts(), device))
        assertTrue(RuleMatcher.evalCond(Cond(type = CondType.LENGTH, minLength = 0, maxLength = 30), facts(), device))
        assertFalse(RuleMatcher.evalCond(Cond(type = CondType.LENGTH, minLength = 0, maxLength = 5), facts(), device))
        assertTrue(RuleMatcher.evalCond(Cond(type = CondType.DEVICE, device = DeviceAspect.SCREEN_ON), facts(), device))
        assertFalse(RuleMatcher.evalCond(Cond(type = CondType.DEVICE, device = DeviceAspect.IN_CALL), facts(), device))
    }
}
