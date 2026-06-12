package com.fortress.trader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fortress.trader.data.AppSettings
import com.fortress.trader.data.EquityPosition
import com.fortress.trader.data.Spread

// --- FORTRESS PREMIUM SYSTEM PALETTE ---
val DarkBackground = Color(0xFF121212)
val SurfaceCard = Color(0xFF1E1E1E)
val BorderGreen = Color(0xFF1B5E20)
val RobinhoodGreen = Color(0xFF00C805)
val AlertOrange = Color(0xFFFF9800)
val LossRed = Color(0xFFFF5252)
val TextWhite = Color(0xFFFFFFFF)
val TextGray = Color(0xFF9E9E9E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                FortressMainArchitecture()
            }
        }
    }
}

private fun money(v: Double): String = String.format("$%,.2f", v)
private fun signedMoney(v: Double): String = (if (v >= 0) "+" else "-") + String.format("$%,.2f", kotlin.math.abs(v))

@Composable
fun FortressMainArchitecture(vm: PlaysViewModel = viewModel()) {
    var currentTab by remember { mutableStateOf("Plays") }

    val settings by vm.settings.collectAsStateWithLifecycle()
    val portfolio by vm.portfolio.collectAsStateWithLifecycle()

    // Auto-load once credentials are present.
    LaunchedEffect(settings.isConfigured) {
        if (settings.isConfigured && !portfolio.loaded && !portfolio.loading) {
            vm.refresh(settings)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = SurfaceCard, tonalElevation = 0.dp) {
                val navigationItems = listOf(
                    Triple("Plays", Icons.Default.PlayArrow, "Plays"),
                    Triple("Positions", Icons.Default.BarChart, "Positions"),
                    Triple("Earnings", Icons.Default.Assignment, "Earnings"),
                    Triple("History", Icons.Default.History, "History"),
                    Triple("Settings", Icons.Default.Settings, "Settings")
                )
                navigationItems.forEach { (route, icon, label) ->
                    NavigationBarItem(
                        selected = currentTab == route,
                        onClick = { currentTab = route },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = RobinhoodGreen,
                            selectedTextColor = RobinhoodGreen,
                            unselectedIconColor = TextGray,
                            unselectedTextColor = TextGray,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentTab) {
                "Plays" -> PlaysDashboardTab(
                    settings = settings,
                    portfolio = portfolio,
                    onRefresh = { vm.refresh(settings) },
                    onGoToSettings = { currentTab = "Settings" }
                )
                "Settings" -> SettingsTerminalTab(
                    settings = settings,
                    connected = portfolio.loaded && portfolio.error == null,
                    onSave = { vm.saveSettings(it) }
                )
                else -> FallbackView(tabName = currentTab)
            }
        }
    }
}

@Composable
fun PlaysDashboardTab(
    settings: AppSettings,
    portfolio: PortfolioState,
    onRefresh: () -> Unit,
    onGoToSettings: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { DashboardHeader(settings, portfolio, onRefresh) }

            if (!settings.isConfigured) {
                item { ConnectPrompt(onGoToSettings) }
                return@LazyColumn
            }

            portfolio.error?.let { err ->
                item { ErrorCard(err) }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderGreen, RoundedCornerShape(10.dp))
                        .background(SurfaceCard, shape = RoundedCornerShape(10.dp))
                        .padding(14.dp)
                ) {
                    Text("PORTFOLIO VALUE", fontSize = 11.sp, color = TextWhite, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(2.dp))
                    val acct = portfolio.account
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = acct?.let { money(it.portfolioValue) } ?: "—",
                            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextWhite
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (acct != null) {
                            Text(
                                text = "${signedMoney(acct.dayChange)} today",
                                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                color = if (acct.dayChange >= 0) RobinhoodGreen else LossRed
                            )
                        }
                    }
                    if (acct != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("${money(acct.cash)} cash", fontSize = 12.sp, color = TextGray)
                    }
                }
            }

            if (portfolio.loading && !portfolio.loaded) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = RobinhoodGreen, modifier = Modifier.size(28.dp))
                    }
                }
            }

            if (portfolio.spreads.isNotEmpty()) {
                item { SectionLabel("OPTION SPREADS") }
                items(portfolio.spreads) { spread -> SpreadCard(spread) }
            }

            if (portfolio.equities.isNotEmpty()) {
                item { SectionLabel("SHARES") }
                items(portfolio.equities) { eq -> EquityCard(eq) }
            }

            if (portfolio.loaded && portfolio.spreads.isEmpty() && portfolio.equities.isEmpty()) {
                item {
                    Text(
                        "No open positions in this Alpaca account.",
                        fontSize = 13.sp, color = TextGray,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardHeader(settings: AppSettings, portfolio: PortfolioState, onRefresh: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = RobinhoodGreen.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Logo",
                        tint = RobinhoodGreen,
                        modifier = Modifier.padding(6.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Fortress ", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                        Text("Options", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = RobinhoodGreen)
                    }
                    val mode = if (settings.paperTrading) "Paper" else "Live"
                    val status = when {
                        !settings.isConfigured -> "● Not connected"
                        portfolio.error != null -> "● Error"
                        portfolio.loaded -> "● $mode • Live data"
                        else -> "● $mode"
                    }
                    Text(
                        text = status,
                        fontSize = 12.sp,
                        color = if (portfolio.error != null) AlertOrange
                        else if (portfolio.loaded) RobinhoodGreen else TextGray
                    )
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextGray)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextWhite)
}

@Composable
private fun ConnectPrompt(onGoToSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderGreen, RoundedCornerShape(12.dp))
            .background(SurfaceCard, shape = RoundedCornerShape(12.dp))
            .padding(18.dp)
    ) {
        Text("Connect your Alpaca account", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextWhite)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Add your Alpaca API key and secret in Settings to load real positions and balance. Keys are stored only on this device.",
            fontSize = 12.sp, color = TextGray, lineHeight = 17.sp
        )
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onGoToSettings,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RobinhoodGreen)
        ) {
            Text("Open Settings", color = DarkBackground, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AlertOrange, RoundedCornerShape(10.dp))
            .background(Color(0xFF2C1D01), shape = RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Text("COULDN'T LOAD", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AlertOrange)
        Spacer(modifier = Modifier.height(4.dp))
        Text(message, fontSize = 12.sp, color = TextWhite, lineHeight = 17.sp)
    }
}

@Composable
fun SpreadCard(spread: Spread) {
    val plPositive = spread.unrealizedPl >= 0
    val accent = if (plPositive) RobinhoodGreen else LossRed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(12.dp))
            .background(SurfaceCard, shape = RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(spread.underlying, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(4.dp), color = RobinhoodGreen.copy(alpha = 0.15f)) {
                        Text(
                            text = "${spread.contracts}x  ·  ${if (spread.isNetCredit) "CREDIT" else "DEBIT"}",
                            color = RobinhoodGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(spread.structureLabel, fontSize = 11.sp, color = TextGray, fontWeight = FontWeight.Medium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Unrealized P/L", fontSize = 10.sp, color = TextGray)
                Text(signedMoney(spread.unrealizedPl), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = accent)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LinearProgressIndicator(
            progress = { spread.profitFraction },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = accent,
            trackColor = Color(0xFF2A2A2A),
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Expiration", fontSize = 11.sp, color = TextGray)
                Text(spread.expiryLabel, fontSize = 12.sp, color = TextWhite, fontWeight = FontWeight.Medium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Market value", fontSize = 11.sp, color = TextGray)
                Text(money(spread.marketValue), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextWhite)
            }
        }
    }
}

@Composable
fun EquityCard(eq: EquityPosition) {
    val accent = if (eq.unrealizedPl >= 0) RobinhoodGreen else LossRed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(12.dp))
            .background(SurfaceCard, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(eq.symbol, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextWhite)
            Text("${eq.qty} sh  ·  ${money(eq.currentPrice)}", fontSize = 11.sp, color = TextGray)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(eq.marketValue), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextWhite)
            Text(signedMoney(eq.unrealizedPl), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = accent)
        }
    }
}

@Composable
fun SettingsTerminalTab(
    settings: AppSettings,
    connected: Boolean,
    onSave: (AppSettings) -> Unit
) {
    var key by remember(settings.alpacaKey) { mutableStateOf(settings.alpacaKey) }
    var secret by remember(settings.alpacaSecret) { mutableStateOf(settings.alpacaSecret) }
    var paper by remember(settings.paperTrading) { mutableStateOf(settings.paperTrading) }
    var automation by remember(settings.automation) { mutableStateOf(settings.automation) }

    Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (connected) RobinhoodGreen.copy(alpha = 0.15f) else Color(0xFF2A2A2A)
                ) {
                    Text(
                        text = if (connected) "✓ Connected" else "Not connected",
                        color = if (connected) RobinhoodGreen else TextGray,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF262626))

            TerminalInputField(label = "Alpaca API Key", value = key, onValueChange = { key = it })
            TerminalInputField(label = "Alpaca API Secret", value = secret, onValueChange = { secret = it }, isProtected = true)

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Paper trading", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextWhite)
                    Text(
                        if (paper) "Using paper-api.alpaca.markets" else "Using LIVE api.alpaca.markets",
                        fontSize = 11.sp, color = if (paper) TextGray else AlertOrange
                    )
                }
                Switch(
                    checked = paper,
                    onCheckedChange = { paper = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextWhite,
                        checkedTrackColor = RobinhoodGreen,
                        uncheckedThumbColor = TextGray,
                        uncheckedTrackColor = Color(0xFF333333)
                    )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Full Automation", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextWhite)
                Switch(
                    checked = automation,
                    onCheckedChange = { automation = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextWhite,
                        checkedTrackColor = RobinhoodGreen,
                        uncheckedThumbColor = TextGray,
                        uncheckedTrackColor = Color(0xFF333333)
                    )
                )
            }

            Button(
                onClick = { onSave(AppSettings(key.trim(), secret.trim(), paper, automation)) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RobinhoodGreen)
            ) {
                Text("Save and Connect", color = DarkBackground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                "Keys are stored only on this device, in the app's private storage. They're sent directly to Alpaca over HTTPS — nothing goes to any other server.",
                fontSize = 11.sp, color = TextGray, lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun TerminalInputField(label: String, value: String, onValueChange: (String) -> Unit, isProtected: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, fontSize = 12.sp, color = TextWhite, modifier = Modifier.padding(bottom = 6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (isProtected) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (isProtected) KeyboardType.Password else KeyboardType.Text),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextWhite,
                unfocusedTextColor = TextWhite,
                focusedBorderColor = RobinhoodGreen,
                unfocusedBorderColor = Color(0xFF444444),
            ),
            shape = RoundedCornerShape(8.dp)
        )
    }
}

@Composable
fun FallbackView(tabName: String) {
    Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = "$tabName Workspace Initializing...", color = TextGray, fontSize = 14.sp)
        }
    }
}
