package com.fortress.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fortress.app.ui.theme.FortressBorder
import com.fortress.app.ui.theme.FortressOffWhite
import com.fortress.app.ui.theme.ProfitGreen
import com.fortress.app.ui.theme.RiskRed
import com.fortress.app.ui.theme.TextPrimary
import com.fortress.app.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.load(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Column {
            Text("Settings", style = MaterialTheme.typography.displayLarge, color = TextPrimary)
            Text(
                "Configure AI and notifications",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        // ── Gemini API Key ────────────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = FortressOffWhite,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Gemini AI",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    StatusChip(state.keyStatus)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Powers the Radar scanner and Risk Officer. Get a free key at aistudio.google.com.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = state.draftKey,
                    onValueChange = viewModel::setDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    placeholder = { Text("AIza...") },
                    singleLine = true,
                    visualTransformation = if (state.keyVisible)
                        VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = viewModel::toggleVisibility) {
                            Icon(
                                if (state.keyVisible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = "Toggle visibility",
                                tint = TextSecondary
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TextPrimary,
                        unfocusedBorderColor = FortressBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = TextPrimary,
                        cursorColor = TextPrimary
                    )
                )

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.saveAndTest(context) },
                        enabled = state.draftKey.isNotBlank() &&
                            state.keyStatus != SettingsViewModel.KeyStatus.TESTING,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TextPrimary,
                            contentColor = Color.White
                        )
                    ) {
                        if (state.keyStatus == SettingsViewModel.KeyStatus.TESTING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Save & Test", fontWeight = FontWeight.Bold)
                        }
                    }

                    if (state.savedKey.isNotBlank()) {
                        TextButton(
                            onClick = { viewModel.clearKey(context) },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Clear",
                                tint = RiskRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("Clear", color = RiskRed)
                        }
                    }
                }
            }
        }

        // ── What Gemini does ──────────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = FortressOffWhite,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("How Gemini is used", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                FeatureRow(
                    emoji = "📡",
                    title = "Radar Scanner",
                    desc = "Gemini 2.0 Flash scans AAPL, MSFT, GOOGL, AMZN, NVDA, SPY, QQQ every 30 seconds and applies the full Fortress filter stack. Returns live trade opportunities ranked by heat score."
                )
                FeatureRow(
                    emoji = "🧠",
                    title = "AI Risk Officer",
                    desc = "Gemini 1.5 Pro analyzes your questions with the Fortress rules baked into its system instruction. Attach a Robinhood screenshot and it reads the options chain directly."
                )
                FeatureRow(
                    emoji = "🔥",
                    title = "Heat Rating",
                    desc = "Flame count (🔥🔥🔥 = hottest) is scored from IV rank, probability of profit, and safety buffer. Higher flames = fatter premium, lower risk."
                )
                FeatureRow(
                    emoji = "🔔",
                    title = "Sell Alerts",
                    desc = "The Armory polls every 60 seconds. When a position crosses 50% profit, a lock-screen notification fires immediately with a one-tap CLOSE button."
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: SettingsViewModel.KeyStatus) {
    when (status) {
        SettingsViewModel.KeyStatus.VALID -> Surface(
            shape = RoundedCornerShape(50),
            color = ProfitGreen.copy(alpha = 0.12f)
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.CheckCircle, null,
                    tint = ProfitGreen, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(4.dp))
                Text("Connected", style = MaterialTheme.typography.labelMedium, color = ProfitGreen)
            }
        }
        SettingsViewModel.KeyStatus.INVALID -> Surface(
            shape = RoundedCornerShape(50),
            color = RiskRed.copy(alpha = 0.12f)
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Error, null,
                    tint = RiskRed, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(4.dp))
                Text("Invalid key", style = MaterialTheme.typography.labelMedium, color = RiskRed)
            }
        }
        SettingsViewModel.KeyStatus.TESTING -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = TextSecondary
        )
        SettingsViewModel.KeyStatus.IDLE -> {}
    }
}

@Composable
private fun FeatureRow(emoji: String, title: String, desc: String) {
    Row {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}
