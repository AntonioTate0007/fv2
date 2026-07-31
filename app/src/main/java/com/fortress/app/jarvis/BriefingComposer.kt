package com.fortress.app.jarvis

import android.content.Context
import android.text.format.DateUtils
import java.util.Calendar

/** Builds the morning briefing text (and a spoken variant) from agenda + reminders. */
object BriefingComposer {

    data class Briefing(val title: String, val body: String, val spoken: String)

    suspend fun compose(context: Context): Briefing {
        val greeting = greeting()
        val events = CalendarReader.upcoming(context, windowMillis = endOfTodayDelta())
        val reminders = ReminderStore.all(context)
            .filter { isToday(it.triggerAtMillis) }
            .sortedBy { it.triggerAtMillis }

        val bodyLines = mutableListOf<String>()
        val spokenBits = mutableListOf("$greeting.")

        if (events.isEmpty()) {
            bodyLines += "📅 Nothing on your calendar today."
            spokenBits += "Your calendar is clear today."
        } else {
            bodyLines += "📅 Today's calendar:"
            events.forEach { bodyLines += "   • ${it.whenLabel(context)} — ${it.title}" }
            spokenBits += "You have ${events.size} event${plural(events.size)} today: " +
                events.joinToString(", ") { "${it.title} at ${it.whenLabel(context)}" } + "."
        }

        if (reminders.isNotEmpty()) {
            bodyLines += ""
            bodyLines += "⏰ Reminders today:"
            reminders.forEach { bodyLines += "   • ${timeLabel(context, it.triggerAtMillis)} — ${it.message}" }
            spokenBits += "And ${reminders.size} reminder${plural(reminders.size)} set."
        }

        if (!CalendarReader.hasPermission(context)) {
            bodyLines += ""
            bodyLines += "ℹ️ Grant calendar access in Jarvis to see your schedule here."
        }

        return Briefing(
            title = "$greeting ☀️",
            body = bodyLines.joinToString("\n"),
            spoken = spokenBits.joinToString(" ")
        )
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 12 -> "Good morning"
            h < 18 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private fun timeLabel(context: Context, millis: Long): String =
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)

    private fun isToday(millis: Long): Boolean {
        val now = Calendar.getInstance()
        val that = Calendar.getInstance().apply { timeInMillis = millis }
        return now.get(Calendar.YEAR) == that.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == that.get(Calendar.DAY_OF_YEAR)
    }

    /** Milliseconds from now until end of today — the window for "today's" events. */
    private fun endOfTodayDelta(): Long {
        val end = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 0)
        }
        return (end.timeInMillis - System.currentTimeMillis()).coerceAtLeast(0)
    }
}
