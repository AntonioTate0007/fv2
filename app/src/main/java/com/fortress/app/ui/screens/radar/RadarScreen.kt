package com.fortress.app.ui.screens.radar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fortress.app.data.model.ScannedTrade
import com.fortress.app.security.BiometricAuthenticator
import com.fortress.app.ui.theme.FortressBorder
import com.fortress.app.ui.theme.FortressOffWhite
import com.fortress.app.ui.theme.ProfitGreen
import com.fortress.app.ui.theme.TextPrimary
import com.fortress.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RadarScreen(
    contentPadding: PaddingValues,
    viewModel: RadarViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? FragmentActivity
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                RadarHeader(
                    tradeCount = state.trades.size,
                    scanning = state.scanning,
                    marketOpen = state.marketOpen,
                    nextScanAtMs = state.nextScanAtMs,
                    lastScannedMs = state.lastScannedMs
                )
                Spacer(Modifier.height(20.dp))
                CapitalDeploymentSlider(
                    selected = state.capital,
                    onSelect = viewModel::setCapital
                )
                Spacer(Modifier.height(8.dp))
            }

            if (state.loading && state.trades.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TextPrimary)
                    }
                }
            }

            items(state.trades, key = { it.id }) { trade ->
                TradeCard(
                    trade = trade,
                    capital = state.capital,
                    deploying = state.deployingTradeId == trade.id,
                    onDeployClick = {
                        val act = activity ?: return@TradeCard
                        BiometricAuthenticator.authorize(
                            activity = act,
                            title = "Authorize ${trade.ticker} deploy",
                            subtitle = trade.strategyLabel
                        ) { result ->
                            when (result) {
                                is BiometricAuthenticator.Result.Success ->
                                    viewModel.deploy(trade, result.token)
                                BiometricAuthenticator.Result.Unavailable ->
                                    scope.launch { snackbar.showSnackbar("Biometric hardware unavailable.") }
                                BiometricAuthenticator.Result.Cancelled -> Unit
                                is BiometricAuthenticator.Result.Error ->
                                    scope.launch { snackbar.showSnackbar("Auth error: ${result.message}") }
                            }
                        }
                    }
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        ) { Snackbar(snackbarData = it, containerColor = TextPrimary, contentColor = Color.White) }
    }
}

@Composable
private fun RadarHeader(
    tradeCount: Int,
    scanning: Boolean,
    marketOpen: Boolean,
    nextScanAtMs: Long?,
    lastScannedMs: Long?
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Radar", style = MaterialTheme.typography.displayLarge, color = TextPrimary)
        Spacer(Modifier.size(12.dp))
        LiveDot(active = scanning)
    }
    Spacer(Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$tradeCount high-probability spreads",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        if (!marketOpen && nextScanAtMs != null) {
            Text(" · ", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text(
                "Market closed · resumes ${formatNextScan(nextScanAtMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        } else if (lastScannedMs != null) {
            Text(" · ", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text(
                "updated ${SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(lastScannedMs))}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (scanning) ProfitGreen else TextSecondary
            )
        }
    }
}

private fun formatNextScan(epochMs: Long): String {
    val nowDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
    val targetDate = Date(epochMs)
    val targetDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(targetDate)
    val pattern = if (nowDay == targetDay) "h:mm a" else "EEE h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(targetDate)
}

/** Pulsing green dot while a scan is in-flight; steady dim dot when idle. */
@Composable
private fun LiveDot(active: Boolean) {
    if (active) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = 0.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotAlpha"
        )
        Box(
            Modifier
                .size(10.dp)
                .alpha(alpha)
                .background(ProfitGreen, CircleShape)
        )
    } else {
        Box(
            Modifier
                .size(10.dp)
                .background(ProfitGreen.copy(alpha = 0.35f), CircleShape)
        )
    }
}

@Composable
private fun CapitalDeploymentSlider(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = FortressOffWhite
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "CAPITAL DEPLOYMENT",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "$$selected",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RadarViewModel.CAPITAL_TIERS.forEach { tier ->
                    val isActive = tier == selected
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isActive) TextPrimary else Color.White,
                        border = if (isActive) null else androidx.compose.foundation.BorderStroke(1.dp, FortressBorder),
                        onClick = { onSelect(tier) }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "$$tier",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isActive) Color.White else TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TradeCard(
    trade: ScannedTrade,
    capital: Int,
    deploying: Boolean,
    onDeployClick: () -> Unit
) {
    val contracts = (capital / 500).coerceAtLeast(1)
    val totalCredit = trade.estimatedCreditPerContract * 100 * contracts

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, FortressBorder)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    trade.ticker,
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                if (trade.flames > 0) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "🔥".repeat(trade.flames),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.size(8.dp))
                SafetyChip(trade.safetyBufferPct)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                trade.strategyLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))
            FilterStackChips(trade)
            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Stat(
                    label = "EST. CREDIT",
                    value = "+$${"%.2f".format(totalCredit)}",
                    valueColor = ProfitGreen,
                    modifier = Modifier.weight(1f)
                )
                Stat(
                    label = "PROB. PROFIT",
                    value = "${(trade.probabilityOfProfit * 100).toInt()}%",
                    modifier = Modifier.weight(1f)
                )
                Stat(
                    label = "EXPIRES",
                    value = trade.expiration.takeLast(5),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onDeployClick,
                enabled = !deploying,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TextPrimary,
                    contentColor = Color.White,
                    disabledContainerColor = TextSecondary
                )
            ) {
                if (deploying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        "DEPLOY × $contracts",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SafetyChip(buffer: Double) {
    val pct = (buffer * 100)
    Surface(
        shape = RoundedCornerShape(50),
        color = ProfitGreen.copy(alpha = 0.12f)
    ) {
        Text(
            text = "${"%.1f".format(pct)}% buffer",
            style = MaterialTheme.typography.labelMedium,
            color = ProfitGreen,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Surfaces the Fortress filter-stack outcomes the user cares about per trade:
 * DTE (theta sweet spot), IV rank (premium fatness), and earnings clearance.
 * Quality + Moat + Rent are already conveyed by ticker, [SafetyChip], and EST. CREDIT.
 */
@Composable
private fun FilterStackChips(trade: ScannedTrade) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(text = "${trade.dte} DTE", tone = ChipTone.Neutral)
        FilterChip(text = "IV ${(trade.ivRank * 100).toInt()}", tone = ivTone(trade.ivRank))
        FilterChip(
            text = if (trade.earningsClear) "No earnings" else "Earnings ⚠",
            tone = if (trade.earningsClear) ChipTone.Safe else ChipTone.Risk
        )
    }
}

private enum class ChipTone { Neutral, Safe, Risk }

private fun ivTone(rank: Double): ChipTone = when {
    rank >= 0.50 -> ChipTone.Safe   // Fat premium — the bot likes nervous markets.
    rank >= 0.30 -> ChipTone.Neutral
    else -> ChipTone.Risk           // IV too low — premium not worth the brick.
}

@Composable
private fun FilterChip(text: String, tone: ChipTone) {
    val (bg, fg) = when (tone) {
        ChipTone.Safe -> ProfitGreen.copy(alpha = 0.12f) to ProfitGreen
        ChipTone.Risk -> com.fortress.app.ui.theme.RiskRed.copy(alpha = 0.12f) to com.fortress.app.ui.theme.RiskRed
        ChipTone.Neutral -> com.fortress.app.ui.theme.FortressOffWhite to TextSecondary
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun Stat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, fontWeight = FontWeight.Bold)
    }
}
