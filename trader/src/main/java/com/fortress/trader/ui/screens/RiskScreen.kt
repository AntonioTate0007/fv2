package com.fortress.trader.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fortress.trader.data.SampleData
import com.fortress.trader.ui.components.FortressCard
import com.fortress.trader.ui.theme.FortressBlack
import com.fortress.trader.ui.theme.FortressGreen
import com.fortress.trader.ui.theme.FortressOrange
import com.fortress.trader.ui.theme.TextSecondary

@Composable
fun RiskScreen(contentPadding: PaddingValues = PaddingValues()) {
    Surface(color = FortressBlack, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "Risk Center",
                color = FortressOrange,
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(Modifier.height(16.dp))

            FortressCard {
                Text("Portfolio Risk Score", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${SampleData.RISK_SCORE} / 100",
                    color = FortressGreen,
                    style = MaterialTheme.typography.displayLarge
                )
                Text("Low overall exposure", color = TextSecondary)
            }

            Spacer(Modifier.height(12.dp))

            FortressCard {
                Text("Earnings Exposure", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                SampleData.earningsExposure.forEach { line ->
                    Text(line, color = FortressOrange)
                }
            }
        }
    }
}
