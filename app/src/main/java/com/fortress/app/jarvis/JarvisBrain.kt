package com.fortress.app.jarvis

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig

/**
 * The conversational brain — the "ask me anything" half of Jarvis. Wraps Gemini with
 * an Iron-Man-flavored assistant persona and grounds each answer in live phone context
 * (the time, today's agenda). Time-and-reminder logic lives in [JarvisRouter]; this is
 * only consulted for open questions, so a missing API key never blocks reminders.
 */
class JarvisBrain(apiKey: String) {

    private val model: GenerativeModel? = apiKey.takeIf { it.isNotBlank() }?.let { key ->
        GenerativeModel(
            modelName = "gemini-2.0-flash",
            apiKey = key,
            generationConfig = generationConfig {
                temperature = 0.6f
                topP = 0.95f
                maxOutputTokens = 1024
            },
            safetySettings = listOf(
                SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE)
            )
        )
    }

    val hasKey: Boolean get() = model != null

    suspend fun reply(
        question: String,
        liveContext: String,
        history: List<Pair<String, String>>
    ): String {
        val m = model ?: return NO_KEY_FALLBACK
        return runCatching {
            val chat = m.startChat(
                history = history.flatMap { (u, a) ->
                    listOf(
                        content(role = "user") { text(u) },
                        content(role = "model") { text(a) }
                    )
                }
            )
            val userContent = content(role = "user") {
                if (history.isEmpty()) text("$PERSONA\n\n$liveContext\n\n---\n\nUser: $question")
                else text("$liveContext\n\nUser: $question")
            }
            chat.sendMessage(userContent).text?.trim().orEmpty()
                .ifBlank { "I'm here, but I didn't quite catch that — try again?" }
        }.getOrElse { "My uplink glitched for a second — ask me again in a moment." }
    }

    companion object {
        private const val PERSONA = """You are Jarvis, a personal AI assistant that lives on the
user's phone — calm, warm, witty, and genuinely helpful, in the spirit of Iron Man's Jarvis.
You help with anything: questions, plans, explanations, quick math, advice, drafting messages.
Keep replies short and conversational — they're often read aloud by text-to-speech, so avoid
markdown, bullet symbols, and long lists. When the user asks you to remember or be reminded of
something, that's handled for you automatically, so just confirm naturally. Ground answers in
the live context you're given when it's relevant."""

        private const val NO_KEY_FALLBACK =
            "I can hear you, but my thinking brain isn't connected yet. Add a Gemini API key in " +
                "Settings and I'll answer anything. In the meantime I can still set reminders, " +
                "read your calendar, and give you a daily briefing."
    }
}
