package com.mark39.assistant.brain

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** A chat brain. Implementations call Gemini or OpenRouter; both share this contract. */
interface LlmClient {
    /** history is prior (user, assistant) turns. Returns the assistant's reply text. */
    suspend fun complete(system: String, history: List<Pair<String, String>>, user: String): String
}

/** Google Gemini (free tier). */
class GeminiClient(private val apiKey: String, model: String = "gemini-2.0-flash") : LlmClient {
    private val gen = GenerativeModel(
        modelName = model,
        apiKey = apiKey,
        generationConfig = generationConfig {
            temperature = 0.6f; topP = 0.95f; maxOutputTokens = 1024
        },
        safetySettings = listOf(
            SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE)
        )
    )

    override suspend fun complete(system: String, history: List<Pair<String, String>>, user: String): String {
        val chat = gen.startChat(
            history = history.flatMap { (u, a) ->
                listOf(content(role = "user") { text(u) }, content(role = "model") { text(a) })
            }
        )
        val msg = content(role = "user") {
            if (history.isEmpty()) text("$system\n\n---\n\n$user") else text(user)
        }
        return chat.sendMessage(msg).text?.trim().orEmpty()
    }
}

/** OpenRouter (free-tier models), OpenAI-compatible chat completions over HTTPS. */
class OpenRouterClient(private val apiKey: String, private val model: String) : LlmClient {

    private val http = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun complete(system: String, history: List<Pair<String, String>>, user: String): String =
        withContext(Dispatchers.IO) {
            val messages = buildList {
                add(Msg("system", system))
                history.forEach { (u, a) -> add(Msg("user", u)); add(Msg("assistant", a)) }
                add(Msg("user", user))
            }
            val body = json.encodeToString(ChatRequest.serializer(), ChatRequest(model, messages))
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://github.com/FatihMakes/Mark-XXXIX-OR")
                .header("X-Title", "Mark XXXIX")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("OpenRouter ${resp.code}: ${text.take(180)}")
                json.decodeFromString(ChatResponse.serializer(), text)
                    .choices.firstOrNull()?.message?.content?.trim().orEmpty()
            }
        }

    @Serializable private data class Msg(val role: String, val content: String)
    @Serializable private data class ChatRequest(val model: String, val messages: List<Msg>)
    @Serializable private data class Choice(val message: Msg)
    @Serializable private data class ChatResponse(val choices: List<Choice> = emptyList())
}
