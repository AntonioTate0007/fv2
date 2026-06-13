package com.mark39.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mark39.assistant.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mark39_prefs")

/** Which brain answers open questions. AUTO uses whichever key is present. */
enum class Provider { AUTO, GEMINI, OPENROUTER }

/**
 * All user-tunable state: the two free-tier API keys (Gemini + OpenRouter), the
 * provider preference, and the chosen OpenRouter model. Keys default to whatever was
 * baked in at build time (usually empty), so nothing sensitive lives in git.
 */
object Prefs {
    private val GEMINI = stringPreferencesKey("gemini_key")
    private val OPENROUTER = stringPreferencesKey("openrouter_key")
    private val PROVIDER = stringPreferencesKey("provider")
    private val OR_MODEL = stringPreferencesKey("or_model")

    const val DEFAULT_OR_MODEL = "deepseek/deepseek-chat-v3-0324:free"

    /**
     * The free OpenRouter models Mark rotates through. When one returns 429
     * (rate-limited upstream), the brain silently rolls to the next — so the user
     * never has to swap models by hand. The user's chosen model is always tried first.
     */
    val FREE_OR_MODELS = listOf(
        "deepseek/deepseek-chat-v3-0324:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "google/gemini-2.0-flash-exp:free",
        "qwen/qwen-2.5-72b-instruct:free",
        "mistralai/mistral-small-3.1-24b-instruct:free",
    )

    fun geminiKey(c: Context): Flow<String> =
        c.dataStore.data.map { it[GEMINI] ?: BuildConfig.DEFAULT_GEMINI_KEY }

    fun openRouterKey(c: Context): Flow<String> =
        c.dataStore.data.map { it[OPENROUTER] ?: BuildConfig.DEFAULT_OPENROUTER_KEY }

    fun provider(c: Context): Flow<Provider> =
        c.dataStore.data.map { runCatching { Provider.valueOf(it[PROVIDER] ?: "AUTO") }.getOrDefault(Provider.AUTO) }

    fun orModel(c: Context): Flow<String> =
        c.dataStore.data.map { it[OR_MODEL]?.takeIf { m -> m.isNotBlank() } ?: DEFAULT_OR_MODEL }

    suspend fun setGeminiKey(c: Context, v: String) = c.dataStore.edit { it[GEMINI] = v.trim() }
    suspend fun setOpenRouterKey(c: Context, v: String) = c.dataStore.edit { it[OPENROUTER] = v.trim() }
    suspend fun setProvider(c: Context, p: Provider) = c.dataStore.edit { it[PROVIDER] = p.name }
    suspend fun setOrModel(c: Context, v: String) = c.dataStore.edit { it[OR_MODEL] = v.trim() }

    // One-shot reads for non-Compose callers.
    suspend fun snapshot(c: Context): Snapshot {
        val p = c.dataStore.data.first()
        return Snapshot(
            geminiKey = p[GEMINI] ?: BuildConfig.DEFAULT_GEMINI_KEY,
            openRouterKey = p[OPENROUTER] ?: BuildConfig.DEFAULT_OPENROUTER_KEY,
            provider = runCatching { Provider.valueOf(p[PROVIDER] ?: "AUTO") }.getOrDefault(Provider.AUTO),
            orModel = p[OR_MODEL]?.takeIf { m -> m.isNotBlank() } ?: DEFAULT_OR_MODEL
        )
    }

    data class Snapshot(
        val geminiKey: String,
        val openRouterKey: String,
        val provider: Provider,
        val orModel: String
    )
}
