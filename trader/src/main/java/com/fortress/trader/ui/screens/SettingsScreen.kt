package com.fortress.trader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fortress.trader.ui.components.FortressCard
import com.fortress.trader.ui.theme.FortressBlack
import com.fortress.trader.ui.theme.FortressGreen
import com.fortress.trader.ui.theme.TextSecondary

@Composable
fun SettingsScreen(contentPadding: PaddingValues = PaddingValues()) {
    Surface(color = FortressBlack, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)

            ConnectionCard(title = "Broker", value = "Alpaca", connected = true)
            ConnectionCard(title = "AI Provider", value = "Gemini", connected = true)
        }
    }
}

@Composable
private fun ConnectionCard(title: String, value: String, connected: Boolean) {
    FortressCard {
        Text(title, color = TextSecondary, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (connected) FortressGreen else TextSecondary, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (connected) "$value Connected" else "$value Disconnected",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
