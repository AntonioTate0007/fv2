package com.thumbshade.app.notif

/** Pulls the useful bits out of a notification's text: codes, links, phone numbers. */
object Extract {
    private val codeKeywords = Regex(
        "(?i)(code|otp|pin|passcode|password|verification|verify|token|2fa|one[- ]time|login|sign[- ]in|security|kod|código|codice|код)"
    )
    // Digits (123456, 123 456), letter-digit groups joined by a hyphen (ABC-123), or one all-caps token (AB12CD).
    private val codeCandidate = Regex(
        "(?<![\\p{L}\\p{N}/.:])(\\d{4,8}|\\d{3} \\d{3}|[A-Z0-9]{3,4}-[A-Z0-9]{3,4}|[A-Z0-9]{5,8})(?![\\p{L}\\p{N}\\-/:])"
    )
    private val url = Regex("(?i)\\b((?:https?://|www\\.)[^\\s<>\"']+[^\\s<>\"'.,;:!?)\\]])")
    private val phone = Regex("(?<![\\d\\w])(\\+?\\d[\\d ()\\-]{6,}\\d)(?![\\d\\w])")

    /** A verification code, if the text looks like it carries one. */
    fun code(text: String): String? {
        if (!codeKeywords.containsMatchIn(text)) return null
        val candidates = codeCandidate.findAll(text).map { it.groupValues[1] }
            .filter { c -> c.any { it.isDigit() } }
            .filterNot { looksLikeYear(it) }
            .toList()
        // Prefer the candidate closest after a keyword; fall back to the first one.
        val keywordEnd = codeKeywords.find(text)?.range?.last ?: 0
        val after = codeCandidate.findAll(text)
            .filter { it.range.first > keywordEnd }
            .map { it.groupValues[1] }
            .firstOrNull { c -> c in candidates }
        return (after ?: candidates.firstOrNull())?.replace(" ", "")?.replace("-", "")
    }

    private fun looksLikeYear(s: String): Boolean = s.length == 4 && s.all { it.isDigit() } && s.toInt() in 1990..2099

    fun links(text: String): List<String> = url.findAll(text).map { m ->
        val v = m.groupValues[1]
        if (v.startsWith("www.", ignoreCase = true)) "https://$v" else v
    }.distinct().toList()

    fun phones(text: String): List<String> = phone.findAll(text).map { it.groupValues[1].trim() }
        .filter { p -> p.count { it.isDigit() } in 7..15 }
        .distinct().toList()
}
