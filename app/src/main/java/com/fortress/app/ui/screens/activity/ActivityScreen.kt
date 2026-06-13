package com.fortress.app.ui.screens.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fortress.app.data.model.ActivityItem
import com.fortress.app.ui.components.money
import com.fortress.app.ui.theme.AutopilotBlue
import com.fortress.app.ui.theme.AutopilotBlueSoft
import com.fortress.app.ui.theme.ProfitGreen
import com.fortress.app.ui.theme.ProfitGreenSoft
import com.fortress.app.ui.theme.TextPrimary
import com.fortress.app.ui.theme.TextSecondary

@Composable
fun ActivityScreen(
    contentPadding: PaddingValues,
    viewModel: ActivityViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Activity", style = MaterialTheme.typography.displayLarge, color = TextPrimary)
        }
        if (state.items.isEmpty() && !state.loading) {
            item {
                Text("No activity yet. When Autopilot trades, it'll show up here.",
                    style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
        items(state.items) { item -> ActivityRow(item) }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem) {
    val (icon, tint, bg) = iconFor(item.type)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = bg, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium,
                color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        item.amount?.let {
            Text(money(it), style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        }
    }
}

private data class RowStyle(val icon: ImageVector, val tint: androidx.compose.ui.graphics.Color, val bg: androidx.compose.ui.graphics.Color)

private fun iconFor(type: String): RowStyle = when (type) {
    "TRADE" -> RowStyle(Icons.Filled.TrendingUp, ProfitGreen, ProfitGreenSoft)
    "REBALANCE" -> RowStyle(Icons.Filled.Sync, AutopilotBlue, AutopilotBlueSoft)
    "FOLLOW" -> RowStyle(Icons.Filled.Verified, AutopilotBlue, AutopilotBlueSoft)
    else -> RowStyle(Icons.Filled.AutoMode, TextSecondary, AutopilotBlueSoft)
}
