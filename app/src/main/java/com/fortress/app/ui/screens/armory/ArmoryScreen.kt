package com.fortress.app.ui.screens.armory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fortress.app.data.model.ActivePosition
import com.fortress.app.security.BiometricAuthenticator
import com.fortress.app.ui.theme.FortressBorder
import com.fortress.app.ui.theme.FortressOffWhite
import com.fortress.app.ui.theme.ProfitGreen
import com.fortress.app.ui.theme.ProfitGreenSoft
import com.fortress.app.ui.theme.RiskRed
import com.fortress.app.ui.theme.TextPrimary
import com.fortress.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun ArmoryScreen(
    contentPadding: PaddingValues,
    viewModel: ArmoryViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Pass context so the VM can fire profit-alert notifications.
    LaunchedEffect(Unit) { viewModel.startPolling() }

    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it); viewModel.consumeToast() }
    }

    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("The Armory", style = MaterialTheme.typography.displayLarge, color = TextPrimary)
                Text(
                    "${state.positions.size} active positions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(12.dp))
            }

            if (state.loading && state.positions.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TextPrimary)
                    }
                }
            }

            items(state.positions, key = { it.id }) { position ->
                PositionCard(
                    position = position,
                    closing = state.closingPositionId == position.id,
                    onCloseClick = {
                        val act = activity ?: return@PositionCard
                        BiometricAuthenticator.authorize(
                            activity = act,
                            title = "Close ${position.ticker}",
                            subtitle = position.strategyLabel
                        ) { result ->
                            when (result) {
                                is BiometricAuthenticator.Result.Success ->
                                    viewModel.close(position, result.token)
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
private fun PositionCard(
    position: ActivePosition,
    closing: Boolean,
    onCloseClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, FortressBorder)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        position.ticker,
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                    Text(
                        position.strategyLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                if (position.atProfitTarget) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = ProfitGreenSoft
                    ) {
                        Text(
                            "50% TARGET",
                            style = MaterialTheme.typography.labelMedium,
                            color = ProfitGreen,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            MoatIndicator(position)
            Spacer(Modifier.height(16.dp))

            Row {
                Stat(
                    label = "ENTRY",
                    value = "+$${"%.2f".format(position.entryPremium)}",
                    valueColor = ProfitGreen,
                    modifier = Modifier.weight(1f)
                )
                Stat(
                    label = "CURRENT",
                    value = "$${"%.2f".format(position.currentPremium)}",
                    modifier = Modifier.weight(1f)
                )
                Stat(
                    label = "P/L",
                    value = "${(position.profitFraction * 100).toInt()}%",
                    valueColor = if (position.profitFraction >= 0.0) ProfitGreen else RiskRed,
                    modifier = Modifier.weight(1f)
                )
                Stat(
                    label = "× CONTRACTS",
                    value = position.contracts.toString(),
                    modifier = Modifier.weight(1f)
                )
            }

            if (position.atProfitTarget) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onCloseClick,
                    enabled = !closing,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ProfitGreen,
                        contentColor = Color.White
                    )
                ) {
                    if (closing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("CLOSE FOR PROFIT", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoatIndicator(position: ActivePosition) {
    val moat = position.moatFraction.toFloat().coerceIn(0f, 1f)
    val safe = moat > 0.05f
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "MOAT",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                "$${"%.2f".format(position.underlyingPrice)} → $${"%.0f".format(position.shortStrike)}",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(FortressOffWhite)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = moat.coerceAtLeast(0.04f))
                    .clip(RoundedCornerShape(50))
                    .background(if (safe) ProfitGreen else RiskRed)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${(position.moatFraction * 100).toInt()}% above floor",
            style = MaterialTheme.typography.labelMedium,
            color = if (safe) ProfitGreen else RiskRed
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
        Spacer(Modifier.size(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, fontWeight = FontWeight.Bold)
    }
}
