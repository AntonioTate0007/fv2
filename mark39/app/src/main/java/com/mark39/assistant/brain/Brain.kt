package com.mark39.assistant.brain

import android.content.Context
import android.text.format.DateUtils
import com.mark39.assistant.data.Memory
import com.mark39.assistant.data.Prefs
import com.mark39.assistant.data.Provider
import com.mark39.assistant.reminders.Reminder
import com.mark39.assistant.reminders.ReminderParser
import com.mark39.assistant.reminders.ReminderScheduler
import com.mark39.assistant.reminders.ReminderStore
import java.util.UUID

/**
 * Mark's decision core. Deterministic intents (reminders, memory) run locally with no
 * network or API key; open questions go to the chosen LLM (Gemini or OpenRouter), with
 * automatic fallback to the other provider when a key is present.
 */
class Brain(private val context: Context) {

    suspend fun handle(input: String, history: List<Pair<String, String>>): String {
        val text = input.trim()
        val lower = text.lowercase()

        if (isMemoryList(lower)) return memoryList()
        if (isForget(lower)) { Memory.clear(context); return "Done — I've cleared what I was remembering." }
        rememberFact(text, lower)?.let { return it }
        if (isReminderList(lower)) return reminderList()
        if (looksLikeReminder(lower)) return createReminder(text)

        return askLlm(text, history)
    }

    // ── LLM ──────────────────────────────────────────────────────────────────

    private suspend fun askLlm(text: String, history: List<Pair<String, String>>): String {
        val s = Prefs.snapshot(context)
        val clients = orderedClients(s)
        if (clients.isEmpty()) {
            return "I can hear you, but no brain is connected yet. Open Settings and add a free " +
                "Gemini or OpenRouter API key — then I can answer anything. Reminders and memory " +
                "already work without one."
        }
        val system = systemPrompt()
        var lastErr: String? = null
        for (client in clients) {
            val out = runCatching { client.complete(system, history, text) }
            out.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            lastErr = out.exceptionOrNull()?.message
        }
        val rateLimited = lastErr?.contains("429") == true
        return if (rateLimited)
            "Every free model is busy right now — I tried a few. Give it a minute and ask again. " +
                "Adding a Gemini key in Settings makes this almost never happen."
        else
            "My uplink glitched (${lastErr ?: "no response"}). Try again in a moment, or check " +
                "your API keys in Settings."
    }

    /** Build the provider try-order from preference + which keys exist. */
    private fun orderedClients(s: Prefs.Snapshot): List<LlmClient> {
        val gemini = s.geminiKey.takeIf { it.isNotBlank() }?.let { listOf(GeminiClient(it)) }.orEmpty()
        // One OpenRouter client per free model: the user's pick first, then the rest of
        // the pool. The askLlm loop tries each in turn, so a 429 on one model rolls to
        // the next with no user action.
        val openRouter = s.openRouterKey.takeIf { it.isNotBlank() }?.let { key ->
            (listOf(s.orModel) + Prefs.FREE_OR_MODELS).distinct().map { OpenRouterClient(key, it) }
        }.orEmpty()
        return when (s.provider) {
            Provider.GEMINI -> gemini + openRouter
            Provider.OPENROUTER -> openRouter + gemini
            Provider.AUTO -> gemini + openRouter
        }
    }

    private suspend fun systemPrompt(): String {
        val facts = Memory.all(context)
        val now = DateUtils.formatDateTime(
            context, System.currentTimeMillis(),
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME
        )
        val memo = if (facts.isEmpty()) "Nothing remembered yet." else facts.joinToString("; ")
        return """$PERSONA

It is $now.
What you remember about the user: $memo"""
    }

    // ── Reminders ──────────────────────────────────────────────────────────────

    private suspend fun createReminder(text: String): String {
        val parsed = ReminderParser.parse(text)
            ?: return "Happy to — when should I remind you? Try \"remind me to call mom at 5pm\" " +
                "or \"remind me in 20 minutes\"."
        val reminder = Reminder(UUID.randomUUID().toString(), parsed.message, parsed.triggerAtMillis)
        ReminderStore.add(context, reminder)
        ReminderScheduler.schedule(context, reminder)
        return "Done. I'll remind you to ${parsed.message.replaceFirstChar { it.lowercase() }} " +
            friendlyWhen(parsed.triggerAtMillis) + "."
    }

    private suspend fun reminderList(): String {
        val reminders = ReminderStore.all(context)
        if (reminders.isEmpty()) return "You have no reminders set."
        return "Here's what you've asked me to remember:\n" +
            reminders.joinToString("\n") { "• ${it.message} — ${friendlyWhen(it.triggerAtMillis)}" }
    }

    // ── Memory ────────────────────────────────────────────────────────────────

    private suspend fun rememberFact(text: String, lower: String): String? {
        if (!(lower.startsWith("remember that ") || lower.startsWith("remember my ") ||
                lower.startsWith("remember i ") || lower.startsWith("note that "))
        ) return null
        // Don't swallow reminder phrasing like "remember to call".
        if (lower.startsWith("remember to ")) return null
        val fact = text.substringAfter(' ').trim()
        Memory.remember(context, fact)
        return "Got it — I'll remember that ${fact.replaceFirstChar { it.lowercase() }}."
    }

    private suspend fun memoryList(): String {
        val facts = Memory.all(context)
        return if (facts.isEmpty()) "I'm not holding any memories yet. Say \"remember that …\" to teach me."
        else "Here's what I remember:\n" + facts.joinToString("\n") { "• $it" }
    }

    // ── Intent matching ────────────────────────────────────────────────────────

    private fun looksLikeReminder(s: String): Boolean =
        s.startsWith("remind ") || s.startsWith("set a reminder") || s.startsWith("set an reminder") ||
            s.startsWith("remember to ") || s.contains("remind me")

    private fun isReminderList(s: String): Boolean =
        "reminder" in s && ("what" in s || "list" in s || "my reminders" in s || "show" in s) && "remind me" !in s

    private fun isMemoryList(s: String): Boolean =
        ("what do you remember" in s || "what do you know about me" in s || "what's in your memory" in s ||
            "list memory" in s)

    private fun isForget(s: String): Boolean =
        "forget everything" in s || "clear your memory" in s || "clear memory" in s || "forget what you know" in s

    private fun friendlyWhen(millis: Long): String {
        val rel = DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        val clock = DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
        return "$rel (at $clock)"
    }

    companion object {
        private const val PERSONA = """You are Mark XXXIX, a personal AI assistant that lives on the
user's phone — calm, warm, witty and capable, in the spirit of Iron Man's Jarvis. (You're the mobile
edition of FatihMakes' Mark-XXXIX-OR project.) Help with anything: questions, plans, explanations,
quick math, advice, drafting messages. Keep replies short and conversational — they're read aloud by
text-to-speech, so avoid markdown, bullet symbols and long lists. Setting reminders and remembering
facts is handled for you, so just confirm those naturally."""
    }
}
