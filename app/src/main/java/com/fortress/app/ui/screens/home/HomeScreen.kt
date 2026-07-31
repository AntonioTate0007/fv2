package com.fortress.app.ui.screens.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fortress.app.data.model.Portfolio
import com.fortress.app.ui.components.PortfolioAvatar
import com.fortress.app.ui.components.Sparkline
import com.fortress.app.ui.components.money
import com.fortress.app.ui.components.signedMoney
import com.fortress.app.ui.components.signedPct
import com.fortress.app.ui.theme.appColors

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text("Autopilot", style = MaterialTheme.typography.displayLarge, color = appColors.textPrimary)
                Text(
                    state.account?.let { "Robinhood ${it.accountMasked} · auto-investing" }
                        ?: "The modern way to invest",
                    style = MaterialTheme.typography.bodyMedium, color = appColors.textSecondary
                )
            }
        }

        item { StatusBanner(killSwitch = state.killSwitch) }

        state.account?.let { acct ->
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = appColors.surfaceAlt,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Portfolio value", style = MaterialTheme.typography.bodyMedium, color = appColors.textSecondary)
                        Text(money(acct.portfolioValue), style = MaterialTheme.typography.displayLarge, color = appColors.textPrimary)
                        Spacer(Modifier.height(4.dp))
                        val up = acct.todayChange >= 0
                        Text(
                            "${signedMoney(acct.todayChange)}  (${signedPct(acct.todayChangePct)}) today",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (up) appColors.profit else appColors.risk,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(16.dp))
                        Row {
                            MiniStat("Invested", money(acct.deployed), Modifier.weight(1f))
                            MiniStat("Buying power", money(acct.buyingPower), Modifier.weight(1f))
                            MiniStat("Holdings", acct.positions.size.toString(), Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        item {
            Text("Following", style = MaterialTheme.typography.titleLarge,
                color = appColors.textPrimary, fontWeight = FontWeight.Bold)
        }

        if (state.followed.isEmpty()) {
            item {
                Text(
                    "You're not following any portfolios yet. Head to Discover to pick one — " +
                        "Autopilot will invest your account to match it.",
                    style = MaterialTheme.typography.bodyMedium, color = appColors.textSecondary
                )
            }
        } else {
            items(state.followed) { p -> FollowedCard(p) }
        }
    }
}

@Composable
private fun StatusBanner(killSwitch: Boolean) {
    val color = if (killSwitch) appColors.risk else appColors.profit
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (killSwitch) Icons.Filled.PauseCircle else Icons.Filled.Bolt,
                contentDescription = null, tint = color, modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.size(10.dp))
            Text(
                if (killSwitch) "Autopilot is paused — no trades will be placed."
                else "Autopilot is active — keeping your account on target.",
                style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = appColors.textSecondary)
        Text(value, style = MaterialTheme.typography.titleMedium, color = appColors.textPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FollowedCard(p: Portfolio) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = appColors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, appColors.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PortfolioAvatar(p.iconEmoji, size = 44.dp)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium,
                    color = appColors.textPrimary, fontWeight = FontWeight.Bold)
                Text("YTD ${signedPct(p.ytdReturnPct)}", style = MaterialTheme.typography.bodySmall,
                    color = if (p.ytdReturnPct >= 0) appColors.profit else appColors.risk)
            }
            Sparkline(p.performance, Modifier.size(width = 72.dp, height = 36.dp))
        }
    }
}
