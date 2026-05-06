package com.fortress.app.data.gemini

import android.util.Base64
import com.fortress.app.data.model.ScannedTrade
import com.fortress.app.data.model.StrategyType
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Wraps Google Generative AI (Gemini) for two jobs:
 *  1. [scanForPlays] — acts as the Fortress filter-stack brain, returning structured trade JSON.
 *  2. [askRiskOfficer] — conversational risk analysis with optional screenshot vision.
 */
class GeminiService(apiKey: String) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // Flash is fast and cheap — ideal for the 30-second scanner loop.
    private val flashModel = GenerativeModel(
        modelName = "gemini-2.0-flash",
        apiKey = apiKey,
        generationConfig = generationConfig {
            temperature = 0.15f       // Low temp = consistent, structured JSON output
            topP = 0.9f
            maxOutputTokens = 2048
        },
        safetySettings = listOf(
            SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE)
        )
    )

    // Pro for the Risk Officer — better reasoning and vision support.
    private val proModel = GenerativeModel(
        modelName = "gemini-1.5-pro",
        apiKey = apiKey,
        generationConfig = generationConfig {
            temperature = 0.4f
            topP = 0.95f
            maxOutputTokens = 1024
        },
        safetySettings = listOf(
            SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE)
        )
    )

    // ── Scanner ───────────────────────────────────────────────────────────────

    suspend fun scanForPlays(capital: Int): List<ScannedTrade> {
        val today = LocalDate.now()
        val expiry7  = today.plusDays(7).toString()
        val expiry14 = today.plusDays(14).toString()

        val prompt = """
You are The Fortress Scanner — a quantitative options trading algorithm.
Today's date: $today. Capital to deploy per block: $$capital.

Scan these tickers for credit spread opportunities: AAPL, MSFT, GOOGL, AMZN, NVDA, SPY, QQQ.

Return ONLY a valid JSON array — no markdown fences, no explanation — of 3 to 5 trades
that satisfy ALL of the Fortress filter criteria:
  1. DTE: 7–14 days (expiration between $expiry7 and $expiry14)
  2. Strategy: PUT_CREDIT_SPREAD or CASH_SECURED_PUT
  3. Short strike: 5–8% below a realistic current market price for that ticker
  4. estimatedCreditPerContract: between 0.35 and 0.80 (dollars per share, i.e. $35–$80 per contract)
  5. safetyBufferPct: between 0.05 and 0.08
  6. ivRank: between 0.45 and 0.90 (reflecting elevated but not extreme IV)
  7. probabilityOfProfit: between 0.78 and 0.92
  8. earningsClear: true (no earnings during the trade window)

Use your best knowledge of current approximate stock prices for each ticker.
Rank the array from highest to lowest heat (ivRank × probabilityOfProfit).

Exact JSON schema for each element:
{
  "id": "TICKER-STRATEGY-STRIKE",
  "ticker": "AAPL",
  "strategy": "PUT_CREDIT_SPREAD",
  "shortStrike": 265.0,
  "longStrike": 260.0,
  "expiration": "YYYY-MM-DD",
  "dte": 10,
  "estimatedCreditPerContract": 0.65,
  "safetyBufferPct": 0.072,
  "underlyingPrice": 288.0,
  "probabilityOfProfit": 0.85,
  "ivRank": 0.71,
  "earningsClear": true
}
        """.trimIndent()

        val response = flashModel.generateContent(prompt)
        val raw = response.text.orEmpty().stripJsonFences()
        return try {
            json.decodeFromString<List<ScannedTrade>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Risk Officer ──────────────────────────────────────────────────────────

    suspend fun askRiskOfficer(
        prompt: String,
        history: List<Pair<String, String>>,   // (userText, aiText) pairs before this turn
        imageBase64: String? = null
    ): String {
        val systemInstruction = RISK_OFFICER_SYSTEM

        // Build a chat session with history so Gemini has context.
        val chat = proModel.startChat(
            history = history.flatMap { (u, a) ->
                listOf(
                    content(role = "user") { text(u) },
                    content(role = "model") { text(a) }
                )
            }
        )

        val userContent = content(role = "user") {
            // Prepend system instruction on the first turn (Gemini Flash/Pro via startChat
            // doesn't support a separate system role in SDK 0.9; inject it as leading text).
            if (history.isEmpty()) text("$systemInstruction\n\n---\n\n$prompt")
            else text(prompt)

            // Attach screenshot bytes if provided.
            imageBase64?.let { b64 ->
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                blob("image/jpeg", bytes)
            }
        }

        return chat.sendMessage(userContent).text.orEmpty()
            .ifBlank { "The Risk Officer had no response. Check your API key in Settings." }
    }

    // ── Test ─────────────────────────────────────────────────────────────────

    /** Quick liveness check — returns true if the key is valid. */
    suspend fun testConnection(): Boolean = try {
        val r = flashModel.generateContent("Reply with exactly: OK")
        r.text?.contains("OK") == true
    } catch (_: Exception) { false }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String.stripJsonFences(): String =
        trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    companion object {
        private val RISK_OFFICER_SYSTEM = """
You are the AI Risk Officer for The Fortress — an institutional-grade options income system.
Your job is to give sharp, concise pre-trade analysis grounded in the Fortress filter stack.

FORTRESS RULES (never deviate from these):
• Quality: AAPL, MSFT, GOOGL, AMZN, NVDA, SPY, QQQ only — mega-caps too large to zero overnight.
• Time: 7–14 DTE — the theta sweet spot where options decay fastest.
• Moat: Short strike must sit 5–8% below the current stock price.
• Rent: Credit must be ${'$'}0.35–${'$'}0.80 per contract (${'$'}35–${'$'}80 per spread). Below ${'$'}35 = not worth the brick. Above ${'$'}80 = strike is too close, moat is compromised.
• Brain checks: Kill any play with earnings during the window. Prefer IV rank > 50%.
• Exit rule: ALWAYS close at 50% profit. Never hold to expiry. Never get greedy.

If the user attaches a Robinhood screenshot, extract the visible options chain data
and grade it against the filter stack. Be explicit: PASS or FAIL each filter.
Flag any red flag immediately. Be direct. No fluff.
        """.trimIndent()
    }
}
