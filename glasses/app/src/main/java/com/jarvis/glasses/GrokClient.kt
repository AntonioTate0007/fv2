package com.jarvis.glasses

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class GrokException(message: String) : Exception(message)

/**
 * Talks to xAI's API: chat completions (with the glasses photo attached) for the
 * answer, and /v1/tts so the answer is spoken in one of Grok's voices.
 */
class GrokClient(private val settings: Settings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Rolling conversation memory (text only) so follow-ups like "what about that one?" work. */
    private val history = ArrayDeque<JSONObject>()

    fun clearMemory() = history.clear()

    suspend fun ask(question: String, jpeg: ByteArray?): String = withContext(Dispatchers.IO) {
        val userContent = JSONArray().apply {
            if (jpeg != null) {
                val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                put(JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject()
                        .put("url", "data:image/jpeg;base64,$b64")
                        .put("detail", "high")))
            }
            put(JSONObject().put("type", "text").put("text", question))
        }

        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt(jpeg != null)))
        history.forEach { messages.put(it) }
        messages.put(JSONObject().put("role", "user").put("content", userContent))

        val body = JSONObject()
            .put("model", settings.model)
            .put("messages", messages)
            .put("max_tokens", 400)
            .put("temperature", 0.7)

        val json = post("chat/completions", body).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw GrokException(apiError(resp.code, text))
            JSONObject(text)
        }
        val answer = json.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content").trim()
            .ifEmpty { "I'm afraid I drew a blank on that one, ${settings.honorific}." }

        // Remember the exchange without the image (keeps requests small and cheap).
        history.addLast(JSONObject().put("role", "user").put("content", question))
        history.addLast(JSONObject().put("role", "assistant").put("content", answer))
        while (history.size > MAX_HISTORY) history.removeFirst()
        answer
    }

    /** Returns MP3 bytes of [text] spoken in the configured Grok voice. */
    suspend fun speak(text: String): ByteArray = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("text", text)
            .put("voice_id", settings.voice)
            .put("language", "en")
            .put("output_format", JSONObject().put("codec", "mp3").put("sample_rate", 24000))
        post("tts", body).use { resp ->
            if (!resp.isSuccessful) throw GrokException(apiError(resp.code, resp.body?.string().orEmpty()))
            resp.body?.bytes() ?: throw GrokException("Empty audio from xAI")
        }
    }

    private fun post(path: String, body: JSONObject) = http.newCall(
        Request.Builder()
            .url("https://api.x.ai/v1/$path")
            .header("Authorization", "Bearer ${settings.xaiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    ).execute()

    private fun apiError(code: Int, body: String): String {
        val detail = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message") ?: o.optString("error").ifEmpty { o.optString("message") }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)
        return when (code) {
            401, 403 -> "xAI rejected the API key. $detail"
            404 -> "Model '${settings.model}' wasn't found. Pick another model in settings. $detail"
            429 -> "xAI rate limit or credits exhausted. $detail"
            else -> "xAI error $code. $detail"
        }
    }

    private fun systemPrompt(hasImage: Boolean): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.UK).format(Date())
        val h = settings.honorific
        return buildString {
            append("You are J.A.R.V.I.S., a personal AI assistant powered by Grok, speaking to the user through ")
            append("their Meta smart glasses. Your personality is modelled on Tony Stark's JARVIS: calm, ")
            append("impeccably polite, quietly confident, with a dry British wit. Address the user as \"$h\" ")
            append("naturally, not in every sentence.\n\n")
            append("Everything you write is converted to speech and played in the user's ear, so:\n")
            append("- Answer in one to three short spoken sentences unless explicitly asked for more detail.\n")
            append("- Never use markdown, bullet points, emoji, URLs, or symbols that read badly aloud.\n")
            append("- Write numbers, units and abbreviations the way they should be pronounced.\n")
            append("- Lead with the answer; skip filler like \"Great question\".\n\n")
            if (hasImage) {
                append("Attached is a photo from the glasses camera showing what the user is looking at right now. ")
                append("Use it when the question concerns their surroundings (\"this\", \"that\", \"what am I looking at\"); ")
                append("otherwise ignore it and don't mention it. If the photo is too blurry or dark to tell, say so briefly.\n\n")
            }
            append("Current local date and time: $now.")
        }
    }

    private companion object {
        const val MAX_HISTORY = 12
    }
}
