package com.fortress.app.data.gemini

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.generationConfig

/**
 * Thin Gemini wrapper. In Autopilot the heavy AI work (picking the portfolio) runs
 * server-side; the app only needs to (a) validate a user-supplied key in Settings
 * and (b) optionally answer "explain this portfolio" style questions.
 */
class GeminiService(apiKey: String) {

    private val model = GenerativeModel(
        modelName = "gemini-2.0-flash",
        apiKey = apiKey,
        generationConfig = generationConfig {
            temperature = 0.3f
            topP = 0.9f
            maxOutputTokens = 1024
        },
        safetySettings = listOf(
            SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE)
        )
    )

    /** Free-form question, e.g. "Why does the AI Flagship hold so much NVDA?" */
    suspend fun ask(prompt: String): String =
        model.generateContent(prompt).text.orEmpty()
            .ifBlank { "No response — check your API key in Settings." }

    /** Quick liveness check — returns true if the key is valid. */
    suspend fun testConnection(): Boolean = try {
        model.generateContent("Reply with exactly: OK").text?.contains("OK") == true
    } catch (_: Exception) { false }
}
