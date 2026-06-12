package com.fortress.app.jarvis

import android.content.Context
import android.text.format.DateUtils
import java.util.UUID

/**
 * The single entry point the UI talks to. Routes a plain-English utterance to the
 * right capability:
 *   • reminder requests  → parsed on-device, scheduled, confirmed
 *   • "what's on today"  → answered straight from the calendar
 *   • "my reminders"     → answered from the store
 *   • email questions    → honest pointer to the (server-backed) inbox feature
 *   • anything else      → the Gemini brain
 *
 * The deterministic intents run with no network and no API key, so the assistant is
 * useful the moment it's installed; the brain only handles open questions.
 */
class JarvisRouter(private val context: Context) {

    suspend fun handle(
        input: String,
        apiKey: String,
        history: List<Pair<String, String>>
    ): String {
        val text = input.trim()
        val lower = text.lowercase()

        if (isAgendaQuery(lower)) return agendaReply()
        if (isReminderListQuery(lower)) return reminderListReply()
        if (looksLikeReminder(lower)) return createReminder(text)
        if (isEmailQuery(lower)) return EMAIL_REPLY

        return JarvisBrain(apiKey).reply(text, liveContext(), history)
    }

    // ── Reminders ────────────────────────────────────────────────────────────

    private suspend fun createReminder(text: String): String {
        val parsed = ReminderParser.parse(text)
            ?: return "Happy to — when should I remind you? Try \"remind me to call mom at 5pm\" " +
                "or \"remind me in 20 minutes\"."
        val reminder = Reminder(
            id = UUID.randomUUID().toString(),
            message = parsed.message,
            triggerAtMillis = parsed.triggerAtMillis
        )
        ReminderStore.add(context, reminder)
        ReminderScheduler.schedule(context, reminder)
        return "Done. I'll remind you to ${decapitalize(parsed.message)} ${friendlyWhen(parsed.triggerAtMillis)}."
    }

    private suspend fun reminderListReply(): String {
        val reminders = ReminderStore.all(context)
        if (reminders.isEmpty()) return "You have no reminders set right now."
        val lines = reminders.joinToString("\n") { "• ${it.message} — ${friendlyWhen(it.triggerAtMillis)}" }
        return "Here's what you've asked me to remember:\n$lines"
    }

    // ── Calendar ─────────────────────────────────────────────────────────────

    private fun agendaReply(): String {
        if (!CalendarReader.hasPermission(context))
            return "I can read your calendar once you grant access — tap the calendar button up top."
        val events = CalendarReader.upcoming(context)
        if (events.isEmpty()) return "Your calendar is clear — nothing coming up."
        val lines = events.joinToString("\n") { "• ${it.whenLabel(context)} — ${it.title}" }
        return "Here's what's coming up:\n$lines"
    }

    // ── Context for the brain ────────────────────────────────────────────────

    private fun liveContext(): String {
        val now = DateUtils.formatDateTime(
            context, System.currentTimeMillis(),
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME
        )
        val agenda = if (CalendarReader.hasPermission(context)) {
            val events = CalendarReader.upcoming(context, limit = 5)
            if (events.isEmpty()) "Calendar is clear."
            else "Next up: " + events.joinToString("; ") { "${it.title} at ${it.whenLabel(context)}" }
        } else "Calendar access not granted."
        return "[Live context] It is $now. $agenda"
    }

    // ── Intent matching ──────────────────────────────────────────────────────

    private fun looksLikeReminder(s: String): Boolean =
        s.startsWith("remind ") || s.startsWith("set a reminder") || s.startsWith("set an reminder") ||
            s.startsWith("remember to ") || s.contains("remind me")

    private fun isReminderListQuery(s: String): Boolean =
        ("reminder" in s || "reminders" in s) &&
            ("what" in s || "list" in s || "my " in s || "show" in s || "any " in s) &&
            !looksLikeReminder(s.removePrefix("what are ").removePrefix("show "))

    private fun isAgendaQuery(s: String): Boolean =
        ("calendar" in s || "agenda" in s || "schedule" in s ||
            "what's on" in s || "whats on" in s || "my day" in s) &&
            "remind" !in s

    private fun isEmailQuery(s: String): Boolean =
        ("email" in s || "inbox" in s || "gmail" in s || "mail" in s) && "remind" !in s

    // ── Formatting ───────────────────────────────────────────────────────────

    private fun friendlyWhen(millis: Long): String {
        val rel = DateUtils.getRelativeTimeSpanString(
            millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        ).toString()
        val clock = DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
        return "$rel (at $clock)"
    }

    private fun decapitalize(s: String): String =
        s.replaceFirstChar { it.lowercase() }

    companion object {
        private const val EMAIL_REPLY =
            "Reading your email lives on the Fortress server (it connects to Gmail there) rather " +
                "than on the phone yet. For now I've got your calendar, reminders, briefings, and " +
                "any question you throw at me — email is the next thing I'll learn."
    }
}
