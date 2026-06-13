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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.fortress.app.ui.components.money
import com.fortress.app.ui.components.pct
import com.fortress.app.ui.theme.AutopilotBlue
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
    val s = state.settings

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.displayLarge, color = TextPrimary)

        // ── Account ───────────────────────────────────────────────────────────
        Card("Brokerage") {
            RowLine("Connected account", s.account.ifBlank { "••••9584" })
            RowLine("Account type", "Robinhood cash · equities only")
            RowLine("Execution", "Autopilot agent (official)")
        }

        // ── Kill switch ───────────────────────────────────────────────────────
        Surface(shape = RoundedCornerShape(20.dp), color = FortressOffWhite, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Autopilot", style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        if (s.killSwitch) "Paused — no trades will be placed."
                        else "Active — keeping your account on target.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.killSwitch) RiskRed else ProfitGreen
                    )
                }
                Switch(
                    checked = !s.killSwitch,
                    onCheckedChange = { viewModel.setKillSwitch(!it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ProfitGreen
                    )
                )
            }
        }

        // ── Guardrails ─────────────────────────────────────────────────────────
        Card("Guardrails") {
            SliderRow(
                label = "Capital to deploy",
                value = money(s.allocatedCapital),
                fraction = (s.allocatedCapital / 5000.0).toFloat().coerceIn(0f, 1f),
                onChange = { viewModel.setAllocatedCapital((it * 5000.0).coerceIn(50.0, 5000.0)) }
            )
            SliderRow(
                label = "Max per position",
                value = pct(s.maxPositionPct),
                fraction = (s.maxPositionPct / 0.5).toFloat().coerceIn(0f, 1f),
                onChange = { viewModel.setMaxPositionPct((it * 0.5).coerceIn(0.05, 0.5)) }
            )
            SliderRow(
                label = "Rebalance drift threshold",
                value = pct(s.driftThresholdPct),
                fraction = (s.driftThresholdPct / 0.1).toFloat().coerceIn(0f, 1f),
                onChange = { viewModel.setDriftThreshold((it * 0.1).coerceIn(0.01, 0.1)) }
            )
            RowLine("Cadence", s.rebalanceCadence.replaceFirstChar { it.uppercase() })
        }

        // ── Gemini key ───────────────────────────────────────────────────────
        Surface(shape = RoundedCornerShape(20.dp), color = FortressOffWhite, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Gemini AI", style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    StatusChip(state.keyStatus)
                }
                Spacer(Modifier.height(4.dp))
                Text("Optional — powers AI portfolio rationale. Free key at aistudio.google.com.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = state.draftKey,
                    onValueChange = viewModel::setDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    placeholder = { Text("AIza...") },
                    singleLine = true,
                    visualTransformation = if (state.keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = viewModel::toggleVisibility) {
                            Icon(
                                if (state.keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = "Toggle visibility", tint = TextSecondary
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AutopilotBlue,
                        unfocusedBorderColor = FortressBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = AutopilotBlue,
                        cursorColor = AutopilotBlue
                    )
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.saveAndTest(context) },
                        enabled = state.draftKey.isNotBlank() && state.keyStatus != SettingsViewModel.KeyStatus.TESTING,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AutopilotBlue, contentColor = Color.White)
                    ) {
                        if (state.keyStatus == SettingsViewModel.KeyStatus.TESTING) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        } else Text("Save & Test", fontWeight = FontWeight.Bold)
                    }
                    if (state.savedKey.isNotBlank()) {
                        TextButton(onClick = { viewModel.clearKey(context) }, modifier = Modifier.height(48.dp)) {
                            Icon(Icons.Filled.Delete, "Clear", tint = RiskRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Clear", color = RiskRed)
                        }
                    }
                }
            }
        }

        Text(
            "Autopilot trades equities/ETFs in your Robinhood cash account only, within the " +
                "guardrails above. Fully automatic — flip the switch off any time to pause.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary
        )
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = FortressOffWhite, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun RowLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SliderRow(label: String, value: String, fraction: Float, onChange: (Double) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = fraction,
            onValueChange = { onChange(it.toDouble()) },
            colors = SliderDefaults.colors(
                thumbColor = AutopilotBlue,
                activeTrackColor = AutopilotBlue
            )
        )
    }
}

@Composable
private fun StatusChip(status: SettingsViewModel.KeyStatus) {
    when (status) {
        SettingsViewModel.KeyStatus.VALID -> Surface(shape = RoundedCornerShape(50), color = ProfitGreen.copy(alpha = 0.12f)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = ProfitGreen, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(4.dp))
                Text("Connected", style = MaterialTheme.typography.labelMedium, color = ProfitGreen)
            }
        }
        SettingsViewModel.KeyStatus.INVALID -> Surface(shape = RoundedCornerShape(50), color = RiskRed.copy(alpha = 0.12f)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Error, null, tint = RiskRed, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(4.dp))
                Text("Invalid key", style = MaterialTheme.typography.labelMedium, color = RiskRed)
            }
        }
        SettingsViewModel.KeyStatus.TESTING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = TextSecondary)
        SettingsViewModel.KeyStatus.IDLE -> {}
    }
}
