package com.fortress.trader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fortress.trader.data.SampleData
import com.fortress.trader.data.model.RiskLevel
import com.fortress.trader.ui.components.FortressCard
import com.fortress.trader.ui.theme.FortressBlack
import com.fortress.trader.ui.theme.FortressGreen
import com.fortress.trader.ui.theme.FortressOrange
import com.fortress.trader.ui.theme.FortressRed
import com.fortress.trader.ui.theme.TextSecondary

@Composable
fun PortfolioScreen(contentPadding: PaddingValues = PaddingValues()) {
    Surface(color = FortressBlack, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp + contentPadding.calculateTopPadding(),
                bottom = 16.dp + contentPadding.calculateBottomPadding()
            )
        ) {
            item {
                Text(
                    "Portfolio",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.height(12.dp))
            }
            items(SampleData.positions) { position ->
                FortressCard(modifier = Modifier.padding(bottom = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(position.ticker, style = MaterialTheme.typography.headlineMedium)
                        RiskBadge(position.risk)
                    }
                    Text(position.strategy, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text("Premium: ${position.premium}")
                }
            }
        }
    }
}

@Composable
private fun RiskBadge(risk: RiskLevel) {
    val color = when (risk) {
        RiskLevel.LOW -> FortressGreen
        RiskLevel.MEDIUM -> FortressOrange
        RiskLevel.HIGH -> FortressRed
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            "${risk.label} Risk",
            color = color,
            style = MaterialTheme.typography.labelLarge
        )
    }
}
