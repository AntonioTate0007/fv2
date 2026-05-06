package com.fortress.app.ui.screens.chat

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fortress.app.data.model.ChatMessage
import com.fortress.app.ui.theme.FortressBorder
import com.fortress.app.ui.theme.FortressOffWhite
import com.fortress.app.ui.theme.TextPrimary
import com.fortress.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(
    contentPadding: PaddingValues,
    viewModel: ChatViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        viewModel.setImageUri(uri)
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding()
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("AI Risk Officer", style = MaterialTheme.typography.displayLarge, color = TextPrimary)
            Text(
                "Pre-trade analysis · attach a screenshot to analyze a play",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        // ── Message list ──────────────────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.messages, key = { it.id }) { msg ->
                ChatBubble(msg)
            }
            if (state.sending) {
                item { TypingIndicator() }
            }
        }

        // ── Staged image thumbnail ────────────────────────────────────────────
        if (state.pendingImageUri != null) {
            PendingImageStrip(
                uri = state.pendingImageUri!!,
                onClear = viewModel::clearImage
            )
        }

        // ── Composer ──────────────────────────────────────────────────────────
        Composer(
            value = state.draft,
            sending = state.sending,
            hasImage = state.pendingImageUri != null,
            onChange = viewModel::setDraft,
            onPickImage = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
            onSend = { viewModel.send() }
        )
    }
}

// ── Chat bubble ───────────────────────────────────────────────────────────────

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val isUser = msg.author == ChatMessage.Author.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            // Image thumbnail if this message has an attached screenshot.
            if (msg.imageUri != null) {
                val uri = Uri.parse(msg.imageUri)
                AttachedImageThumbnail(uri = uri, isUser = isUser)
                Spacer(Modifier.height(4.dp))
            }

            // Text bubble (skip if it's just the auto-generated image-only label).
            if (msg.text.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomEnd = if (isUser) 4.dp else 18.dp,
                        bottomStart = if (isUser) 18.dp else 4.dp
                    ),
                    color = if (isUser) TextPrimary else FortressOffWhite
                ) {
                    Text(
                        text = msg.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isUser) Color.White else TextPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

/** Loads and displays the image URI as a rounded thumbnail in the bubble. */
@Composable
private fun AttachedImageThumbnail(uri: Uri, isUser: Boolean) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            } catch (_: Exception) { null }
        }
    }

    bitmap?.let { bmp ->
        Image(
            bitmap = bmp,
            contentDescription = "Attached screenshot",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 220.dp, height = 160.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomEnd = if (isUser) 4.dp else 18.dp,
                        bottomStart = if (isUser) 18.dp else 4.dp
                    )
                )
        )
    }
}

// ── Typing indicator ──────────────────────────────────────────────────────────

@Composable
private fun TypingIndicator() {
    Row {
        Surface(shape = RoundedCornerShape(18.dp), color = FortressOffWhite) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = TextSecondary,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.size(8.dp))
                Text("Risk Officer is thinking...", color = TextSecondary)
            }
        }
    }
}

// ── Pending image strip ───────────────────────────────────────────────────────

@Composable
private fun PendingImageStrip(uri: Uri, onClear: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            } catch (_: Exception) { null }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = FortressOffWhite
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            bitmap?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            }
            Spacer(Modifier.size(10.dp))
            Text(
                "Screenshot attached",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove image",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── Composer ──────────────────────────────────────────────────────────────────

@Composable
private fun Composer(
    value: String,
    sending: Boolean,
    hasImage: Boolean,
    onChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onSend: () -> Unit
) {
    val canSend = (value.isNotBlank() || hasImage) && !sending

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 📎 Attach button
            IconButton(
                onClick = onPickImage,
                enabled = !sending,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (hasImage) TextPrimary else TextSecondary,
                    disabledContentColor = TextSecondary
                )
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Attach screenshot")
            }

            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (hasImage) "Add a note, or send as-is…"
                        else "Ask the Risk Officer…",
                        color = TextSecondary
                    )
                },
                shape = RoundedCornerShape(24.dp),
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TextPrimary,
                    unfocusedBorderColor = FortressBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = TextPrimary
                )
            )

            Spacer(Modifier.size(8.dp))

            // Send button
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = TextPrimary,
                    contentColor = Color.White,
                    disabledContainerColor = TextSecondary,
                    disabledContentColor = Color.White
                )
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
