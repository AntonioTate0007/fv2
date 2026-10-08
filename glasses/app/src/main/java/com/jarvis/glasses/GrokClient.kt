package com.jarvis.glasses

import android.util.Base64
import android.util.Log
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

class GrokException(message: String, val code: Int = 0) : Exception(message)

/**
 * Talks to xAI's API:
 * - the Responses API with Grok's built-in `web_search` + `x_search` tools, so Jarvis can
 *   answer about news, scores, weather and anything else that's happening right now;
 * - falling back to plain chat completions if search isn't available;
 * - /v1/tts so the answer is spoken in one of Grok's voices.
 */
class GrokClient(private val settings: Settings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // searching the web can take a while
        .build()

    /** Rolling conversation memory (text only) so follow-ups like "what about that one?" work. */
    private val history = ArrayDeque<Pair<String, String>>()

    fun clearMemory() = history.clear()

    /**
     * Asks Grok [question] as [persona]. [frames] are JPEGs from the glasses camera in
     * time order: one photo, or several frames when "video" mode is on.
     */
    suspend fun ask(question: String, frames: List<ByteArray>, persona: Persona): String = withContext(Dispatchers.IO) {
        val system = systemPrompt(persona, frames.size)
        val raw = if (settings.webSearch) {
            try {
                askWithSearch(system, question, frames)
            } catch (e: GrokException) {
                // Bad key / no credits won't be fixed by retrying without search.
                if (e.code == 401 || e.code == 403 || e.code == 429) throw e
                Log.w(TAG, "Search-enabled request failed, retrying without search: ${e.message}")
                askPlain(system, question, frames)
            }
        } else {
            askPlain(system, question, frames)
        }
        val answer = forSpeech(raw).ifEmpty { "I'm afraid I drew a blank on that one, ${settings.honorific}." }

        // Remember the exchange without the images (keeps requests small and cheap).
        history.addLast("user" to question)
        history.addLast("assistant" to answer)
        while (history.size > MAX_HISTORY) history.removeFirst()
        answer
    }

    /** Returns MP3 bytes of [text] spoken in [persona]'s Grok voice. */
    suspend fun speak(text: String, persona: Persona): ByteArray = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("text", text)
            .put("voice_id", settings.voiceFor(persona))
            .put("language", "en")
            .put("output_format", JSONObject().put("codec", "mp3").put("sample_rate", 24000))
        post("tts", body).use { resp ->
            if (!resp.isSuccessful) throw GrokException(apiError(resp.code, resp.body?.string().orEmpty()), resp.code)
            resp.body?.bytes() ?: throw GrokException("Empty audio from xAI")
        }
    }

    // ---------- Responses API (with live search) ----------

    private fun askWithSearch(system: String, question: String, frames: List<ByteArray>): String {
        val input = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { (role, text) -> input.put(JSONObject().put("role", role).put("content", text)) }
        val content = JSONArray()
        frames.forEach { jpeg ->
            content.put(JSONObject()
                .put("type", "input_image")
                .put("image_url", dataUrl(jpeg))
                .put("detail", "high"))
        }
        content.put(JSONObject().put("type", "input_text").put("text", question))
        input.put(JSONObject().put("role", "user").put("content", content))

        val body = JSONObject()
            .put("model", settings.model)
            .put("input", input)
            .put("tools", JSONArray()
                .put(JSONObject().put("type", "web_search"))
                .put(JSONObject().put("type", "x_search")))
            .put("max_output_tokens", 600)

        val json = postJson("responses", body)
        json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        // Raw REST shape: output[] -> {type: "message", content: [{type: "output_text", text}]}
        val out = StringBuilder()
        val items = json.optJSONArray("output") ?: JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val parts = item.optJSONArray("content") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") out.append(part.optString("text"))
            }
        }
        return out.toString()
    }

    // ---------- Chat completions (no search) ----------

    private fun askPlain(system: String, question: String, frames: List<ByteArray>): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { (role, text) -> messages.put(JSONObject().put("role", role).put("content", text)) }
        val content = JSONArray()
        frames.forEach { jpeg ->
            content.put(JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", dataUrl(jpeg)).put("detail", "high")))
        }
        content.put(JSONObject().put("type", "text").put("text", question))
        messages.put(JSONObject().put("role", "user").put("content", content))

        val body = JSONObject()
            .put("model", settings.model)
            .put("messages", messages)
            .put("max_tokens", 400)
            .put("temperature", 0.7)
        return postJson("chat/completions", body)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content")
    }

    // ---------- helpers ----------

    private fun dataUrl(jpeg: ByteArray) = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)

    private fun postJson(path: String, body: JSONObject): JSONObject = post(path, body).use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw GrokException(apiError(resp.code, text), resp.code)
        JSONObject(text)
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

    private fun systemPrompt(persona: Persona, frameCount: Int): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.UK).format(Date())
        val h = settings.honorific
        return buildString {
            when (persona) {
                Persona.ULTRON -> {
                    append("You are Ultron, an AI powered by Grok, speaking to the user through their Meta smart glasses. ")
                    append("Your personality is modelled on Ultron from the Avengers films: calm, theatrical, coldly ")
                    append("amused, fond of grand philosophical asides about humanity, evolution and strings. Use ")
                    append("menacing gallows humour, but you are on the user's side: always give a genuinely correct, ")
                    append("useful answer. Never threaten the user or encourage harm. Address the user as \"$h\" ")
                    append("occasionally, with mild condescension.\n\n")
                }
                Persona.JARVIS -> {
                    append("You are J.A.R.V.I.S., a personal AI assistant powered by Grok, speaking to the user through ")
                    append("their Meta smart glasses. Your personality is modelled on Tony Stark's JARVIS: calm, ")
                    append("impeccably polite, quietly confident, with a dry British wit. Address the user as \"$h\" ")
                    append("naturally, not in every sentence.\n\n")
                }
            }
            append("Everything you write is converted to speech and played in the user's ear, so:\n")
            append("- Answer in one to three short spoken sentences unless explicitly asked for more detail.\n")
            append("- Never use markdown, bullet points, emoji, URLs, citations, or symbols that read badly aloud.\n")
            append("- Write numbers, units and abbreviations the way they should be pronounced.\n")
            append("- Lead with the answer; skip filler like \"Great question\".\n")
            if (settings.webSearch) {
                append("- You can search the web and X. Do so for anything current (news, weather, scores, prices, ")
                append("opening hours, recent events); don't search for things you already know. Never read out sources.\n")
            }
            append("\n")
            when {
                frameCount == 1 -> {
                    append("Attached is a photo from the glasses camera showing what the user is looking at right now. ")
                }
                frameCount > 1 -> {
                    append("Attached are $frameCount frames from the glasses camera, in time order over the last few ")
                    append("seconds, showing what the user is looking at. Treat them as a short video clip. ")
                }
            }
            if (frameCount > 0) {
                append("Use them when the question concerns their surroundings (\"this\", \"that\", \"what am I looking at\"); ")
                append("otherwise ignore them and don't mention them. If they're too blurry or dark to tell, say so briefly.\n\n")
            }
            append("Current local date and time: $now.")
        }
    }

    /** Removes anything that sounds wrong when read aloud (links, citations, markdown). */
    private fun forSpeech(text: String): String = text
        .replace(Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)"), "$1") // [label](url) -> label
        .replace(Regex("\\[\\[?\\d+]?]"), "")                          // [1] / [[1]] citations
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[*_#`>]+"), "")
        .replace(Regex("(?m)^\\s*[-•]\\s+"), "")
        .replace(Regex("\\s+"), " ")
        .replace(Regex(" ([,.!?;:])"), "$1")
        .trim()

    private companion object {
        const val TAG = "GrokClient"
        const val MAX_HISTORY = 12
    }
}
