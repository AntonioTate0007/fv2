package com.fortress.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String,
    val author: Author,
    val text: String,
    val timestampMillis: Long,
    /** Local content URI of an attached camera-roll image (user messages only). */
    val imageUri: String? = null
) {
    enum class Author { USER, AI }
}

@Serializable
data class RiskOfficerRequest(
    val prompt: String,
    val capitalContext: Int? = null,
    /** Base64-encoded JPEG of the attached Robinhood screenshot, if any. */
    val imageBase64: String? = null
)

@Serializable
data class RiskOfficerResponse(val reply: String)
