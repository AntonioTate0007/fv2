package com.thumbshade.app.rules

import java.util.concurrent.ConcurrentHashMap

object RuleMatcher {
    private val regexCache = ConcurrentHashMap<String, Result<Regex>>()

    fun matches(rule: Rule, facts: NotifFacts, device: DeviceSnapshot): Boolean {
        if (!rule.enabled) return false
        if (rule.apps.isNotEmpty() && facts.pkg !in rule.apps) return false
        if (facts.pkg in rule.excludeApps) return false
        return evalGroup(rule.root, facts, device)
    }

    fun evalGroup(group: CondGroup, facts: NotifFacts, device: DeviceSnapshot): Boolean {
        val results = group.conditions.asSequence().map { evalCond(it, facts, device) } +
            group.groups.asSequence().map { evalGroup(it, facts, device) }
        val list = results.toList()
        val raw = when {
            list.isEmpty() -> true
            group.matchAll -> list.all { it }
            else -> list.any { it }
        }
        return raw != group.negate
    }

    fun evalCond(c: Cond, f: NotifFacts, d: DeviceSnapshot): Boolean {
        val raw = when (c.type) {
            CondType.TEXT -> textMatches(c, f)
            CondType.CATEGORY -> f.category in c.categories
            CondType.IMPORTANCE -> f.importance in c.minImportance..c.maxImportance
            CondType.FLAG -> when (c.flag) {
                Flag.GROUP_CHAT -> f.isGroupChat
                Flag.FROM_CONTACT -> f.fromContact
                Flag.HAS_PICTURE -> f.hasPicture
                Flag.HAS_REPLY -> f.hasReply
                Flag.ONGOING -> f.ongoing
                Flag.SILENT -> f.silent
                Flag.REACTION -> f.isReaction
            }
            CondType.TIME -> inTimeWindow(c, d)
            CondType.LENGTH -> (f.title.length + f.text.length) in c.minLength..c.maxLength
            CondType.DEVICE -> when (c.device) {
                DeviceAspect.SCREEN_ON -> d.screenOn
                DeviceAspect.IN_CALL -> d.inCall
                DeviceAspect.RINGER_NORMAL -> d.ringerMode == 2
                DeviceAspect.RINGER_VIBRATE -> d.ringerMode == 1
                DeviceAspect.RINGER_SILENT -> d.ringerMode == 0
                DeviceAspect.DND_ON -> d.dndOn
                DeviceAspect.CHARGING -> d.charging
            }
        }
        return raw != c.negate
    }

    /** Words are separated by `|`; any one matching is enough. */
    fun splitWords(words: String): List<String> =
        words.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    private fun textMatches(c: Cond, f: NotifFacts): Boolean {
        val haystack = when (c.field) {
            TextField.ANY -> f.title + "\n" + f.text
            TextField.TITLE -> f.title
            TextField.TEXT -> f.text
            TextField.APP -> f.appName
        }
        return when (c.wordMode) {
            WordMode.CONTAINS -> splitWords(c.words).any { haystack.contains(it, ignoreCase = true) }
            WordMode.WHOLE_WORD -> splitWords(c.words).any { w ->
                cachedRegex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(w) + "(?![\\p{L}\\p{N}])")?.containsMatchIn(haystack) == true
            }
            WordMode.REGEX -> c.words.isNotBlank() && cachedRegex(c.words)?.containsMatchIn(haystack) == true
        }
    }

    private fun cachedRegex(pattern: String): Regex? =
        regexCache.getOrPut(pattern) { runCatching { Regex(pattern) } }.getOrNull()

    fun inTimeWindow(c: Cond, d: DeviceSnapshot): Boolean {
        val start = c.startMinute
        val end = c.endMinute
        return if (start <= end) {
            d.dayOfWeek in c.days && d.minuteOfDay in start until end
        } else {
            // Wraps past midnight: the early-morning part belongs to the previous day's window.
            val previousDay = if (d.dayOfWeek == 1) 7 else d.dayOfWeek - 1
            (d.minuteOfDay >= start && d.dayOfWeek in c.days) ||
                (d.minuteOfDay < end && previousDay in c.days)
        }
    }

    fun isValidRegex(pattern: String): Boolean = runCatching { Regex(pattern) }.isSuccess
}
