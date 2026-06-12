package com.fortress.app.ui.screens.jarvis

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fortress.app.data.model.ChatMessage
import com.fortress.app.data.preferences.AppPreferences
import com.fortress.app.jarvis.JarvisRouter
import com.fortress.app.jarvis.VoiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Drives the Jarvis assistant screen: holds the conversation, sends each turn through
 * [JarvisRouter], and speaks every reply aloud via [VoiceManager] so it feels like a
 * real assistant talking back.
 */
class JarvisViewModel(app: Application) : AndroidViewModel(app) {

    private val router = JarvisRouter(app.applicationContext)
    private val voice = VoiceManager.get(app.applicationContext)

    @Volatile private var apiKey: String = ""

    data class UiState(
        val messages: List<ChatMessage> = listOf(SEED),
        val draft: String = "",
        val sending: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            AppPreferences.geminiKeyFlow(getApplication()).collect { apiKey = it }
        }
    }

    fun setDraft(text: String) = _state.update { it.copy(draft = text) }

    /** Voice transcript came back from the system recognizer — send it straight through. */
    fun onVoiceResult(text: String) {
        if (text.isBlank()) return
        _state.update { it.copy(draft = text) }
        send()
    }

    fun send() {
        val text = _state.value.draft.trim()
        if (text.isEmpty() || _state.value.sending) return

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            author = ChatMessage.Author.USER,
            text = text,
            timestampMillis = System.currentTimeMillis()
        )
        val history = _state.value.messages
            .zipWithNext()
            .filter { (a, b) -> a.author == ChatMessage.Author.USER && b.author == ChatMessage.Author.AI }
            .map { (u, a) -> u.text to a.text }

        _state.update { it.copy(messages = it.messages + userMsg, draft = "", sending = true) }

        viewModelScope.launch {
            val reply = runCatching { router.handle(text, apiKey, history) }
                .getOrElse { "Something went sideways on my end — try me again." }
            val aiMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                author = ChatMessage.Author.AI,
                text = reply,
                timestampMillis = System.currentTimeMillis()
            )
            _state.update { it.copy(messages = it.messages + aiMsg, sending = false) }
            voice.speak(reply)
        }
    }

    fun stopSpeaking() = voice.stop()

    companion object {
        private val SEED = ChatMessage(
            id = "jarvis-seed",
            author = ChatMessage.Author.AI,
            text = "Hi, I'm Jarvis — I live on your phone now. Tap the mic and talk, or type.\n\n" +
                "Try: \"Remind me to call mom at 5pm\", \"What's on my calendar today?\", " +
                "or just ask me anything.",
            timestampMillis = System.currentTimeMillis()
        )
    }
}
