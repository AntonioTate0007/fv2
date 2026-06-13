package com.fortress.app.ui.screens.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.People
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fortress.app.data.model.Portfolio
import com.fortress.app.ui.components.Sparkline
import com.fortress.app.ui.components.pct
import com.fortress.app.ui.components.signedPct
import com.fortress.app.ui.theme.AutopilotBlue
import com.fortress.app.ui.theme.FortressBorder
import com.fortress.app.ui.theme.FortressOffWhite
import com.fortress.app.ui.theme.ProfitGreen
import com.fortress.app.ui.theme.RiskRed
import com.fortress.app.ui.theme.TextPrimary
import com.fortress.app.ui.theme.TextSecondary

@Composable
fun DiscoverScreen(
    contentPadding: PaddingValues,
    viewModel: DiscoverViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text("Find your", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                Text("Portfolio", style = MaterialTheme.typography.displayLarge, color = TextPrimary)
            }
        }
        item {
            Text("Top performers", style = MaterialTheme.typography.titleLarge,
                color = TextPrimary, fontWeight = FontWeight.Bold)
        }
        items(state.portfolios) { p ->
            PortfolioCard(
                portfolio = p,
                following = p.id in state.followedIds,
                busy = state.busyId == p.id,
                onClick = { viewModel.open(p) },
                onFollow = { viewModel.toggleFollow(p) }
            )
        }
    }

    state.selected?.let { p ->
        PortfolioDetailDialog(
            portfolio = p,
            following = p.id in state.followedIds,
            busy = state.busyId == p.id,
            onFollow = { viewModel.toggleFollow(p) },
            onDismiss = { viewModel.closeDetail() }
        )
    }
}

@Composable
private fun PortfolioCard(
    portfolio: Portfolio,
    following: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onFollow: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, FortressBorder),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(portfolio.iconEmoji, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(portfolio.name, style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(portfolio.tagline, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(signedPct(portfolio.ytdReturnPct),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (portfolio.ytdReturnPct >= 0) ProfitGreen else RiskRed,
                        fontWeight = FontWeight.Bold)
                    Text("YTD", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Sparkline(portfolio.performance, Modifier.fillMaxWidth().height(44.dp))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.People, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("%,d".format(portfolio.followers), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(Modifier.weight(1f))
                FollowButton(following, busy, onFollow)
            }
        }
    }
}

@Composable
private fun FollowButton(following: Boolean, busy: Boolean, onClick: () -> Unit) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AutopilotBlue)
        return
    }
    if (following) {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, ProfitGreen)
        ) {
            Icon(Icons.Filled.Check, null, tint = ProfitGreen, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("Following", color = ProfitGreen, fontWeight = FontWeight.Bold)
        }
    } else {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = AutopilotBlue, contentColor = Color.White)
        ) { Text("Follow", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun PortfolioDetailDialog(
    portfolio: Portfolio,
    following: Boolean,
    busy: Boolean,
    onFollow: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {
            LazyColumn(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(portfolio.iconEmoji, style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(portfolio.name, style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text("by ${portfolio.managerLabel}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }
                item { Sparkline(portfolio.performance, Modifier.fillMaxWidth().height(80.dp)) }
                item {
                    Text(portfolio.description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
                if (portfolio.rationale.isNotBlank()) {
                    item {
                        Surface(shape = RoundedCornerShape(14.dp), color = FortressOffWhite) {
                            Column(Modifier.padding(14.dp)) {
                                Text("🧠 AI rationale", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                Spacer(Modifier.height(4.dp))
                                Text(portfolio.rationale, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            }
                        }
                    }
                }
                item {
                    Text("Holdings", style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary, fontWeight = FontWeight.Bold)
                }
                items(portfolio.holdings) { h ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.ticker, style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text(h.name, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        Text(pct(h.weight), style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                        FollowButton(following, busy, onFollow)
                    }
                }
            }
        }
    }
}
