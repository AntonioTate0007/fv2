package com.mark39.assistant.reminders

import java.util.Calendar
import java.util.Locale

/** A reminder request parsed out of plain English: what to say, and when to fire. */
data class ParsedReminder(val message: String, val triggerAtMillis: Long)

/**
 * On-device natural-language parser for reminder requests — runs with no network and
 * no API key so "remind me to call mom at 5pm" always schedules. Handles:
 *   • "in 20 minutes" / "in 2 hours" / "in 3 days"
 *   • "at 5", "at 5pm", "at 5:30 pm"  (rolls to the next future occurrence)
 *   • "tomorrow [at H]", "tonight", "this evening", "this afternoon", "this morning"
 * Returns null when no time phrase is found.
 */
object ReminderParser {

    private val LEAD = Regex(
        "^(please\\s+)?(remind me (that |to )?|set (a |an )?reminder (to |that |for )?|remember to )",
        RegexOption.IGNORE_CASE
    )
    private val IN_REL = Regex("\\bin\\s+(\\d+)\\s*(minute|min|hour|hr|day)s?\\b", RegexOption.IGNORE_CASE)
    private val AT_ABS = Regex("\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b", RegexOption.IGNORE_CASE)

    fun parse(input: String, now: Long = System.currentTimeMillis()): ParsedReminder? {
        var text = input.trim().removeSuffix(".")
        text = LEAD.replace(text, "").trim()

        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val lower = text.lowercase(Locale.US)
        var dayOffset = 0
        var hasTomorrow = false
        if ("tomorrow" in lower) { dayOffset = 1; hasTomorrow = true }

        IN_REL.find(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            val unit = m.groupValues[2].lowercase(Locale.US)
            val field = when {
                unit.startsWith("min") -> Calendar.MINUTE
                unit.startsWith("h") -> Calendar.HOUR_OF_DAY
                else -> Calendar.DAY_OF_MONTH
            }
            cal.add(field, n)
            return ParsedReminder(cleanMessage(text, m.value), cal.timeInMillis)
        }

        val absMatch = AT_ABS.find(text)
        if (absMatch == null) {
            val (hour, hit) = when {
                "tonight" in lower -> 20 to "tonight"
                "this evening" in lower -> 18 to "this evening"
                "this afternoon" in lower -> 15 to "this afternoon"
                "this morning" in lower || "in the morning" in lower -> 9 to "morning"
                else -> -1 to ""
            }
            if (hour >= 0) {
                cal.add(Calendar.DAY_OF_MONTH, dayOffset)
                setClock(cal, hour, 0)
                if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
                return ParsedReminder(cleanMessage(text, hit, "tomorrow"), cal.timeInMillis)
            }
            if (hasTomorrow) {
                cal.add(Calendar.DAY_OF_MONTH, dayOffset)
                setClock(cal, 9, 0)
                return ParsedReminder(cleanMessage(text, "tomorrow"), cal.timeInMillis)
            }
            return null
        }

        var hour = absMatch.groupValues[1].toIntOrNull() ?: return null
        val minute = absMatch.groupValues[2].toIntOrNull() ?: 0
        val meridiem = absMatch.groupValues[3].lowercase(Locale.US)
        when (meridiem) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        cal.add(Calendar.DAY_OF_MONTH, dayOffset)
        setClock(cal, hour, minute)
        if (cal.timeInMillis <= now) {
            if (meridiem.isEmpty() && hour < 12) setClock(cal, hour + 12, minute)
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return ParsedReminder(cleanMessage(text, absMatch.value, "tomorrow"), cal.timeInMillis)
    }

    private fun setClock(cal: Calendar, hour: Int, minute: Int) {
        cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    }

    private fun cleanMessage(text: String, vararg phrases: String): String {
        var out = text
        for (p in phrases) if (p.isNotEmpty()) out = out.replace(p, "", ignoreCase = true)
        out = out.replace(Regex("\\s+"), " ").trim().trim(',', '.', ' ')
        out = out.removePrefix("to ").removeSuffix(" to").trim()
        return out.ifBlank { "Reminder" }.replaceFirstChar { it.uppercase() }
    }
}
