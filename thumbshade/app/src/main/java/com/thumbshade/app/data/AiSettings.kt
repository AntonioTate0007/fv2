package com.thumbshade.app.data

import kotlinx.serialization.Serializable

/** The "Smart" features. Everything runs on the phone; nothing is sent anywhere. */
@Serializable
data class AiSettings(
    // Summaries
    val summaries: Boolean = true,
    /** Use Gemini Nano (Android AICore) when the phone has it. */
    val useNano: Boolean = true,
    val summaryMinMessages: Int = 4,

    // Priority
    val learn: Boolean = true,
    val smartOrder: Boolean = false,
    val minimizeLow: Boolean = false,
    val showBadges: Boolean = true,

    // Replies and actions
    val smartReplies: Boolean = true,
    val smartActions: Boolean = true,

    // Focus batching
    val digest: Boolean = false,
    val digestTimes: List<Int> = listOf(12 * 60, 18 * 60),
    /** Also keep "normal" notifications quiet; only urgent and personal ones make a sound. */
    val quietNormal: Boolean = false,
    val digestNeverApps: Set<String> = emptySet(),
)
