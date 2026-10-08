package com.thumbshade.app.rules

object Categorize {
    private val socialPkgs = listOf(
        "com.facebook", "com.instagram", "com.twitter", "com.x.android", "com.zhiliaoapp", "com.ss.android",
        "com.snapchat", "com.reddit", "com.linkedin", "com.pinterest", "com.tumblr", "org.joinmastodon", "com.bsky",
    )
    private val messagePkgs = listOf(
        "com.whatsapp", "org.telegram", "org.thunderdog", "com.discord", "com.Slack", "com.facebook.orca",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "org.thoughtcrime.securesms",
        "com.viber", "jp.naver.line", "com.skype", "com.microsoft.teams", "com.google.android.apps.dynamite",
    )
    private val emailPkgs = listOf("com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email", "ch.protonmail", "com.yahoo.mobile.client.android.mail", "me.bluemail", "com.fsck.k9", "eu.faircode.email")

    /**
     * [category] is Notification.category (may be null). [isMessaging] means MessagingStyle.
     */
    fun of(category: String?, pkg: String, isMessaging: Boolean, isMedia: Boolean, hasProgress: Boolean): Cat {
        when (category) {
            "call" -> return Cat.CALL
            "missed_call" -> return Cat.MISSED_CALL
            "msg" -> return Cat.MESSAGE
            "email" -> return Cat.EMAIL
            "social" -> return Cat.SOCIAL
            "event" -> return Cat.EVENT
            "reminder" -> return Cat.REMINDER
            "alarm" -> return Cat.ALARM
            "promo" -> return Cat.PROMO
            "progress" -> return Cat.PROGRESS
            "transport" -> return Cat.TRANSPORT
            "navigation" -> return Cat.NAVIGATION
            "sys" -> return Cat.SYSTEM
            "service" -> return Cat.SERVICE
            "err" -> return Cat.ERROR
            "status" -> return Cat.STATUS
            "recommendation" -> return Cat.RECOMMENDATION
            "workout" -> return Cat.WORKOUT
            "stopwatch" -> return Cat.STOPWATCH
        }
        return when {
            isMedia -> Cat.TRANSPORT
            isMessaging -> Cat.MESSAGE
            messagePkgs.any { pkg.startsWith(it) } -> Cat.MESSAGE
            emailPkgs.any { pkg.startsWith(it) } -> Cat.EMAIL
            socialPkgs.any { pkg.startsWith(it) } -> Cat.SOCIAL
            hasProgress -> Cat.PROGRESS
            pkg == "android" || pkg.startsWith("com.android.systemui") -> Cat.SYSTEM
            else -> Cat.OTHER
        }
    }

    private val reaction = Regex(
        "(?i)^(reacted|liked|loved|laughed at|emphasized|disliked|questioned)\\b|\\breacted .+ to |\\breacted to\\b|\\bliked (your|a) message\\b"
    )

    fun isReaction(text: String): Boolean = reaction.containsMatchIn(text)
}
