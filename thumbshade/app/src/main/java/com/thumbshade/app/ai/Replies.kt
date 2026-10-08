package com.thumbshade.app.ai

/** Reply chips for a message, from what the message is asking. Works offline. */
object Replies {
    enum class Kind { TEXT, LOCATION }
    data class Reply(val text: String, val kind: Kind = Kind.TEXT)

    private fun r(vararg texts: String) = texts.map { Reply(it) }

    private data class Intent(val pattern: Regex, val replies: List<Reply>)

    private val intents = listOf(
        Intent(
            Regex("(?i)\\b(where (are|r) (you|u)|where you at|wya|wru|where are y'?all|how far (away )?are you|you close|almost here|eta\\b|are you (coming|on (your|the) way))"),
            listOf(Reply("Share my location", Kind.LOCATION)) + r("On my way!", "Be there in 5", "Running a bit late"),
        ),
        Intent(Regex("(?i)\\b(what time|when (are|do|will|should|can)|what'?s the time|how long)\\b"), r("Soon", "In 10 minutes", "Not sure yet, I'll let you know")),
        Intent(Regex("(?i)\\b(call me|can (you|u) call|give me a call|phone me|ring me)\\b"), r("Calling you now", "Can't talk right now, I'll call you later", "Can you text instead?")),
        Intent(Regex("(?i)\\b(are you (free|around|available|busy)|you free|free (today|tonight|tomorrow|later)|got a (minute|sec))\\b"), r("Yes, I'm free", "Busy right now", "Free later today")),
        Intent(Regex("(?i)\\b(thank(s| you)|thx|ty|appreciate it|cheers)\\b"), r("You're welcome!", "Anytime 👍", "No problem")),
        Intent(Regex("(?i)\\b(sorry|my bad|apologies|apologize)\\b"), r("No worries!", "It's okay", "Don't worry about it")),
        Intent(Regex("(?i)\\b(congrat(s|ulations)|well done|proud of you|happy birthday|happy anniversary)\\b"), r("Thank you! 🙏", "Thanks so much!", "❤️")),
        Intent(Regex("(?i)\\b(love you|luv u|miss you|miss u)\\b"), r("Love you too ❤️", "Miss you too", "❤️")),
        Intent(Regex("(?i)\\b(good morning|morning!|gm)\\b"), r("Good morning!", "Morning ☀️")),
        Intent(Regex("(?i)\\b(good night|gn|night night|sleep well)\\b"), r("Good night!", "Sleep well 😴")),
        Intent(Regex("(?i)^\\s*(hi|hey|hello|yo|hiya|sup|what'?s up)\\b"), r("Hey!", "Hi! What's up?", "Hey, how are you?")),
        Intent(Regex("(?i)\\b(how are (you|u)|how'?s it going|how have you been|how r u|you ok|are you okay)\\b"), r("Good, thanks! You?", "All good 👍", "Doing well!")),
        Intent(Regex("(?i)\\b(want to|wanna|shall we|should we|let'?s|up for|down (for|to)|join (us|me))\\b.*\\?|\\b(dinner|lunch|coffee|drinks|movie)\\b.*\\?"), r("Sounds good!", "I'm in 👍", "Can't make it, sorry")),
        Intent(Regex("(?i)^\\s*(are|is|can|could|do|does|did|will|would|have|has|should|may|shall)\\b.*\\?\\s*$"), r("Yes", "No", "Not sure")),
    )

    private val genericQuestion = r("Let me check", "I'll get back to you")
    private val generic = r("👍", "Okay", "Got it")

    /**
     * Up to [max] replies for [message]. [system] replies Android already suggested come first,
     * then ours, without duplicates.
     */
    fun suggest(message: String, system: List<String> = emptyList(), max: Int = 4): List<Reply> {
        val text = message.trim()
        if (text.isEmpty()) return system.take(max).map { Reply(it) }
        val ours = intents.firstOrNull { it.pattern.containsMatchIn(text) }?.replies
            ?: if (text.endsWith("?")) genericQuestion + r("Yes", "No") else generic
        val out = LinkedHashMap<String, Reply>()
        // Location first when it applies: it's the one you can't type with one thumb.
        ours.filter { it.kind == Kind.LOCATION }.forEach { out[it.text.lowercase()] = it }
        system.forEach { s -> if (s.isNotBlank()) out.putIfAbsent(s.trim().lowercase(), Reply(s.trim())) }
        ours.forEach { out.putIfAbsent(it.text.lowercase(), it) }
        return out.values.take(max)
    }
}
