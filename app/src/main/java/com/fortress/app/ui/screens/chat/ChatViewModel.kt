package com.fortress.app.ui.screens.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fortress.app.data.model.ChatMessage
import com.fortress.app.data.repository.FortressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FortressRepository(app.applicationContext)

    data class UiState(
        val messages: List<ChatMessage> = listOf(SEED),
        val draft: String = "",
        val sending: Boolean = false,
        val pendingImageUri: Uri? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setDraft(text: String) = _state.update { it.copy(draft = text) }
    fun setImageUri(uri: Uri?) = _state.update { it.copy(pendingImageUri = uri) }
    fun clearImage() = _state.update { it.copy(pendingImageUri = null) }

    fun send(capitalContext: Int? = null) {
        val text = _state.value.draft.trim()
        val imageUri = _state.value.pendingImageUri
        if (text.isEmpty() && imageUri == null) return
        if (_state.value.sending) return

        val displayText = text.ifEmpty { "📸 Analyze this screenshot." }
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            author = ChatMessage.Author.USER,
            text = displayText,
            timestampMillis = System.currentTimeMillis(),
            imageUri = imageUri?.toString()
        )

        // Capture conversation history before appending new message (for Gemini context).
        val historyPairs = _state.value.messages
            .zipWithNext()
            .filter { (a, b) -> a.author == ChatMessage.Author.USER && b.author == ChatMessage.Author.AI }
            .map { (u, a) -> u.text to a.text }

        _state.update { it.copy(messages = it.messages + userMsg, draft = "", pendingImageUri = null, sending = true) }

        viewModelScope.launch {
            val ctx = getApplication<Application>().applicationContext
            val base64 = imageUri?.let { uri ->
                withContext(Dispatchers.IO) { encodeImageUri(ctx, uri) }
            }

            val reply = runCatching {
                repo.askRiskOfficer(displayText, capitalContext, base64, historyPairs)
            }.getOrElse { "I can't reach the Risk Officer right now (${it.message})." }

            val aiMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                author = ChatMessage.Author.AI,
                text = reply,
                timestampMillis = System.currentTimeMillis()
            )
            _state.update { it.copy(messages = it.messages + aiMsg, sending = false) }
        }
    }

    companion object {
        private val SEED = ChatMessage(
            id = "seed",
            author = ChatMessage.Author.AI,
            text = "I'm your AI Risk Officer. Ask me to analyze a ticker, size a block, or " +
                "stress-test a spread. Attach a Robinhood screenshot with the 📎 button, " +
                "or try: \"Analyze AAPL for a \$1,000 block next week.\"\n\n" +
                "Add your Gemini API key in Settings to enable live AI analysis.",
            timestampMillis = System.currentTimeMillis()
        )

        private fun encodeImageUri(ctx: android.content.Context, uri: Uri): String? = try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val maxDim = 1024
            var s = 1; while (s * 2 <= maxOf(opts.outWidth, opts.outHeight) / maxDim) s *= 2
            val decode = BitmapFactory.Options().apply { inSampleSize = s }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decode) } ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            bmp.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }
}
