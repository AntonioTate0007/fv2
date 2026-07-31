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
fun DashboardScreen(contentPadding: PaddingValues = PaddingValues()) {
    Surface(color = FortressBlack, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "FORTRESS AI",
                color = FortressGreen,
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                "Options Command Center",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(20.dp))

            FortressCard {
                Text("Portfolio Value", style = MaterialTheme.typography.titleMedium)
                Text(
                    SampleData.PORTFOLIO_VALUE,
                    style = MaterialTheme.typography.displayLarge
                )
                Text(SampleData.TODAY_CHANGE, color = FortressGreen)
            }

            Spacer(Modifier.height(16.dp))

            FortressCard {
                Text("⚠ Earnings Lockout", color = FortressOrange, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text("ORCL earnings within 24 hours.")
                Text("AI Recommendation: Close before earnings.", color = TextSecondary)
            }
        }
    }
}
