package com.mark39.assistant.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mark39.assistant.data.ChatMessage
import com.mark39.assistant.ui.theme.ReactorCyan
import com.mark39.assistant.ui.theme.SpacePanel
import com.mark39.assistant.ui.theme.SpacePanelSoft
import com.mark39.assistant.ui.theme.TextDim
import com.mark39.assistant.ui.theme.TextOnDark

@Composable
fun AssistantScreen(
    onOpenSettings: () -> Unit,
    viewModel: AssistantViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val speech = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) viewModel.onVoiceResult(spoken)
        }
    }
    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Mark…")
        }
        runCatching { speech.launch(intent) }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("MARK XXXIX", style = MaterialTheme.typography.displayLarge, color = ReactorCyan)
                Text("on-phone assistant", style = MaterialTheme.typography.labelLarge, color = TextDim)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TextDim)
            }
        }

        // Transcript
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.messages, key = { it.id }) { msg -> Bubble(msg) }
            if (state.thinking) item { Text("Mark is thinking…", color = TextDim, modifier = Modifier.padding(8.dp)) }
        }

        // Composer
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = { listen() },
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = ReactorCyan, contentColor = MaterialTheme.colorScheme.onPrimary)
            ) { Icon(Icons.Filled.Mic, contentDescription = "Talk to Mark") }

            OutlinedTextField(
                value = state.draft,
                onValueChange = viewModel::setDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask or tell Mark…", color = TextDim) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ReactorCyan,
                    unfocusedBorderColor = SpacePanelSoft,
                    focusedTextColor = TextOnDark,
                    unfocusedTextColor = TextOnDark
                )
            )

            IconButton(
                onClick = { viewModel.send() },
                enabled = state.draft.isNotBlank() && !state.thinking,
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = ReactorCyan, contentColor = MaterialTheme.colorScheme.onPrimary)
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (message.fromUser) ReactorCyan else SpacePanel,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (message.fromUser) 16.dp else 4.dp,
                bottomEnd = if (message.fromUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (message.fromUser) MaterialTheme.colorScheme.onPrimary else TextOnDark,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
