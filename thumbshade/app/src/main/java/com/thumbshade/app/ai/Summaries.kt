package com.thumbshade.app.ai

/**
 * Extractive summaries that work on any phone: who is talking, how much, what about, and the
 * latest line. Gemini Nano replaces these with a written summary where the phone has it.
 */
object Summaries {
    data class Line(val sender: String, val text: String)

    private val stop = setOf(
        "the", "a", "an", "and", "or", "but", "to", "of", "in", "on", "at", "for", "is", "it", "i", "you", "we", "me", "my",
        "your", "our", "this", "that", "be", "are", "was", "with", "so", "just", "have", "has", "do", "did", "not", "no",
        "yes", "ok", "okay", "lol", "haha", "im", "i'm", "its", "it's", "what", "when", "where", "how", "who", "can",
        "will", "would", "about", "they", "he", "she", "them", "there", "here", "too", "u", "ur", "if", "then", "up",
        "out", "all", "get", "got", "go", "going", "like", "yeah", "yep", "nah", "oh", "hey", "hi", "hello", "thanks",
        "thank", "from", "by", "as", "am", "pm", "now", "today", "tomorrow", "tonight", "dont", "don't", "really",
        "one", "some", "any", "more", "very", "also", "should", "could", "want", "need", "know", "think", "see", "let",
        "let's", "lets", "good", "great", "sure", "yes", "new", "via", "re", "fwd",
    )

    fun names(senders: List<String>): String {
        val distinct = senders.filter { it.isNotBlank() }.distinct()
        return when (distinct.size) {
            0 -> ""
            1 -> distinct[0]
            2 -> "${distinct[0]} and ${distinct[1]}"
            3 -> "${distinct[0]}, ${distinct[1]} and ${distinct[2]}"
            else -> "${distinct[0]}, ${distinct[1]} and ${distinct.size - 2} others"
        }
    }

    /** The few words a conversation keeps coming back to. */
    fun topics(texts: List<String>, max: Int = 3): List<String> {
        val counts = HashMap<String, Int>()
        texts.forEach { t ->
            Regex("[\\p{L}][\\p{L}'’-]{2,}").findAll(t.lowercase()).map { it.value.trim('\'', '’', '-') }
                .filter { it.length >= 3 && it !in stop && !it.startsWith("http") }
                .distinct()
                .forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts.entries.filter { it.value >= 2 || texts.size <= 3 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
            .take(max).map { it.key }
    }

    /** "Sarah, Tom and 3 others · 12 messages · about dinner, tonight · latest: “see you at 8”". */
    fun conversation(lines: List<Line>, timeMention: String? = null): String {
        if (lines.isEmpty()) return ""
        // Most recent speakers first.
        val who = names(lines.asReversed().map { it.sender })
        val about = topics(lines.map { it.text })
        val latest = lines.last().text.replace(Regex("\\s+"), " ").trim().let { if (it.length > 70) it.take(67).trimEnd() + "…" else it }
        return buildList {
            if (who.isNotBlank()) add(who)
            add("${lines.size} message" + if (lines.size == 1) "" else "s")
            val aboutParts = about + listOfNotNull(timeMention)
            if (aboutParts.isNotEmpty()) add("about " + aboutParts.joinToString(", "))
            add("latest: “$latest”")
        }.joinToString(" · ")
    }

    data class Brief(val title: String, val text: String)

    /** "7 from Gmail: Amazon (3), Chase, LinkedIn · latest: “Your order shipped”". */
    fun group(appName: String, items: List<Brief>): String {
        if (items.isEmpty()) return ""
        val byTitle = items.groupingBy { it.title.ifBlank { appName } }.eachCount().entries.sortedByDescending { it.value }
        val who = byTitle.take(3).joinToString(", ") { if (it.value > 1) "${it.key} (${it.value})" else it.key } +
            if (byTitle.size > 3) " and ${byTitle.size - 3} more" else ""
        val latest = items.first().let { it.text.ifBlank { it.title } }.replace(Regex("\\s+"), " ").trim()
            .let { if (it.length > 60) it.take(57).trimEnd() + "…" else it }
        return "${items.size} from $appName: $who" + if (latest.isNotBlank()) " · latest: “$latest”" else ""
    }

    /** Plain text handed to an on-device model. */
    fun transcript(lines: List<Line>): String = lines.takeLast(40).joinToString("\n") { l ->
        (if (l.sender.isNotBlank()) l.sender + ": " else "") + l.text
    }
}
