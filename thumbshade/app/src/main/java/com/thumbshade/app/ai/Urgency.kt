package com.thumbshade.app.ai

import com.thumbshade.app.rules.Cat

/** How much a notification deserves to interrupt. */
enum class Urgency(val label: String, val rank: Int) {
    LOW("Low (promotions, likes, newsletters)", 0),
    NORMAL("Normal", 1),
    PERSONAL("Personal (people writing to you)", 2),
    URGENT("Urgent", 3),
}

/**
 * A small on-device classifier: hand-tuned signals (category, words, who it's from) adjusted
 * by what you usually do with notifications like it ([learned], -1 ignore … 1 always open).
 */
object UrgencyModel {
    data class Features(
        val cat: Cat,
        val importance: Int,
        val text: String,
        val fromContact: Boolean = false,
        val isReaction: Boolean = false,
        val ongoing: Boolean = false,
        val hasCode: Boolean = false,
        val isGroupChat: Boolean = false,
    )

    private val urgentWords = Regex(
        "(?i)\\b(urgent|emergency|asap|911|immediately|right now|call me( now)?|help me|accident|hospital|fraud alert|suspicious (sign[- ]in|activity)|security alert|your (card|account) (was|has been) (locked|blocked|used))\\b"
    )
    private val lowWords = Regex(
        "(?i)(\\d{1,2} ?% off|\\bsale\\b|\\bdiscount|\\bdeals?\\b|\\boffer\\b|\\bcoupon|\\bpromo|newsletter|unsubscribe|limited time|free shipping|recommended for you|you might like|trending|\\bliked your\\b|\\breacted to\\b|followed you|new follower|weekly (digest|recap|summary)|don'?t miss|last chance|flash sale|\\bshop now\\b|watch now|new episode|is live\\b|went live|people you may know|\\bpoints? (expire|balance)|rate (us|your)|how did we do|survey)"
    )

    fun classify(f: Features, learned: Float = 0f): Urgency {
        if (f.ongoing) return Urgency.NORMAL
        val base = when {
            f.cat == Cat.CALL || f.cat == Cat.MISSED_CALL || f.cat == Cat.ALARM -> Urgency.URGENT
            urgentWords.containsMatchIn(f.text) -> Urgency.URGENT
            f.hasCode -> Urgency.URGENT
            f.isReaction -> Urgency.LOW
            f.cat == Cat.PROMO || f.cat == Cat.RECOMMENDATION -> Urgency.LOW
            f.importance in 1..2 -> Urgency.LOW
            lowWords.containsMatchIn(f.text) && !f.fromContact -> Urgency.LOW
            f.cat == Cat.SOCIAL -> Urgency.LOW
            f.fromContact -> Urgency.PERSONAL
            f.cat == Cat.MESSAGE -> if (f.isGroupChat) Urgency.NORMAL else Urgency.PERSONAL
            f.cat == Cat.EVENT || f.cat == Cat.REMINDER -> Urgency.PERSONAL
            else -> Urgency.NORMAL
        }
        if (base == Urgency.URGENT) return base
        // What you do with similar notifications moves them one step either way.
        return when {
            learned >= 0.45f -> Urgency.entries[(base.rank + 1).coerceAtMost(Urgency.PERSONAL.rank)]
            learned <= -0.45f -> Urgency.entries[(base.rank - 1).coerceAtLeast(Urgency.LOW.rank)]
            else -> base
        }
    }

    /** Ordering score: higher = more important = closer to your thumb. */
    fun score(u: Urgency, learned: Float): Float = u.rank + learned * 0.8f
}
