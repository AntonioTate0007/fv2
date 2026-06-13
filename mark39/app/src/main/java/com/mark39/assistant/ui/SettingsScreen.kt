package com.mark39.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mark39.assistant.data.Provider
import com.mark39.assistant.ui.theme.ReactorCyan
import com.mark39.assistant.ui.theme.SpacePanelSoft
import com.mark39.assistant.ui.theme.TextDim
import com.mark39.assistant.ui.theme.TextOnDark

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextDim)
            }
            Text("Settings", style = MaterialTheme.typography.displayLarge, color = TextOnDark)
        }

        Text(
            "Mark's brain runs on free-tier APIs. Add at least one key. Reminders and memory " +
                "work even with none.",
            color = TextDim, style = MaterialTheme.typography.bodyMedium
        )

        KeyField("Gemini API key", state.geminiKey, viewModel::setGeminiKey)
        Text("Get one free at aistudio.google.com/apikey", color = TextDim, style = MaterialTheme.typography.labelLarge)

        KeyField("OpenRouter API key", state.openRouterKey, viewModel::setOpenRouterKey)
        Text("Get one free at openrouter.ai/keys", color = TextDim, style = MaterialTheme.typography.labelLarge)

        Text("Preferred brain", color = TextOnDark, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Provider.entries.forEach { p ->
                FilterChip(
                    selected = state.provider == p,
                    onClick = { viewModel.setProvider(p) },
                    label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ReactorCyan,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }

        KeyField("OpenRouter model", state.orModel, viewModel::setOrModel, secret = false)
        Text(
            "Mark auto-rotates through free models if this one is busy, so you can leave it as-is.",
            color = TextDim, style = MaterialTheme.typography.labelLarge
        )

        Button(
            onClick = { viewModel.save(); onBack() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ReactorCyan, contentColor = MaterialTheme.colorScheme.onPrimary)
        ) { Text("Save") }

        Text(
            "Mobile edition of FatihMakes' Mark-XXXIX-OR (CC BY-NC 4.0). Personal use.",
            color = TextDim, style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, color = TextDim) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ReactorCyan,
            unfocusedBorderColor = SpacePanelSoft,
            focusedTextColor = TextOnDark,
            unfocusedTextColor = TextOnDark
        )
    )
}
