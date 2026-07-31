package com.mark39.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mark39.assistant.brain.Brain
import com.mark39.assistant.data.ChatMessage
import com.mark39.assistant.voice.VoiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class AssistantViewModel(app: Application) : AndroidViewModel(app) {

    private val brain = Brain(app.applicationContext)
    private val voice = VoiceManager.get(app.applicationContext)

    data class UiState(
        val messages: List<ChatMessage> = listOf(SEED),
        val draft: String = "",
        val thinking: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setDraft(text: String) = _state.update { it.copy(draft = text) }

    fun onVoiceResult(text: String) {
        if (text.isBlank()) return
        _state.update { it.copy(draft = text) }
        send()
    }

    fun send() {
        val text = _state.value.draft.trim()
        if (text.isEmpty() || _state.value.thinking) return

        val userMsg = ChatMessage(UUID.randomUUID().toString(), fromUser = true, text = text)
        val history = _state.value.messages
            .zipWithNext()
            .filter { (a, b) -> a.fromUser && !b.fromUser }
            .map { (u, a) -> u.text to a.text }

        _state.update { it.copy(messages = it.messages + userMsg, draft = "", thinking = true) }

        viewModelScope.launch {
            val reply = runCatching { brain.handle(text, history) }
                .getOrElse { "Something went sideways on my end — try me again." }
            val aiMsg = ChatMessage(UUID.randomUUID().toString(), fromUser = false, text = reply)
            _state.update { it.copy(messages = it.messages + aiMsg, thinking = false) }
            voice.speak(reply)
        }
    }

    fun stopSpeaking() = voice.stop()

    companion object {
        private val SEED = ChatMessage(
            id = "seed",
            fromUser = false,
            text = "I'm Mark — your assistant, on your phone. Tap the reactor and talk, or type.\n\n" +
                "Try: \"Remind me to call mom at 5pm\", \"Remember that my anniversary is June 2\", " +
                "or ask me anything. Add a free Gemini or OpenRouter key in Settings to switch my brain on."
        )
    }
}
