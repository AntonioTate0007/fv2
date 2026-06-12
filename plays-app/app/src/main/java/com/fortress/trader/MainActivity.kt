package com.fortress.trader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
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

// --- FORTRESS PREMIUM SYSTEM PALETTE ---
val DarkBackground = Color(0xFF121212)
val SurfaceCard = Color(0xFF1E1E1E)
val BorderGreen = Color(0xFF1B5E20)
val RobinhoodGreen = Color(0xFF00C805)
val AlertOrange = Color(0xFFFF9800)
val DarkOrangeBg = Color(0xFF2C1D01)
val TextWhite = Color(0xFFFFFFFF)
val TextGray = Color(0xFF9E9E9E)

data class TrackedCondor(
    val ticker: String,
    val baselinePrice: Double,
    val currentPrice: Double,
    val strategyLabel: String,
    val putStrikes: String,
    val callStrikes: String,
    val initialCredit: Double,
    val currentMidPrice: Double,
    val targetGtcClose: Double,
    val earningsDate: String,
    val hasCatalystTodayOrTomorrow: Boolean
)

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

@Composable
fun FortressMainArchitecture() {
    var currentTab by remember { mutableStateOf("Plays") }

    // PERSISTENT API STATE CORES
    var alpacaKey by remember { mutableStateOf("ALP_a9876...") }
    var alpacaSecret by remember { mutableStateOf("ALP_sec_321...") }
    var geminiKey by remember { mutableStateOf("GEMINI_v123...") }
    var aiStudioKey by remember { mutableStateOf("GAIS_xyz...") }
    var isAutomationActive by remember { mutableStateOf(true) }

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
                "Plays" -> PlaysDashboardTab(isAutomationActive)
                "Settings" -> SettingsTerminalTab(
                    alpacaKey = alpacaKey, onAlpacaKeyChange = { alpacaKey = it },
                    alpacaSecret = alpacaSecret, onAlpacaSecretChange = { alpacaSecret = it },
                    geminiKey = geminiKey, onGeminiKeyChange = { geminiKey = it },
                    aiStudioKey = aiStudioKey, onAiStudioKeyChange = { aiStudioKey = it },
                    isAutomationActive = isAutomationActive, onAutomationToggle = { isAutomationActive = it }
                )
                else -> FallbackView(tabName = currentTab)
            }
        }
    }
}

@Composable
fun PlaysDashboardTab(isAutomationActive: Boolean) {
    // Pipeline tracking data showcasing the impending Oracle catalyst tomorrow afternoon
    val portfolioPositions = listOf(
        TrackedCondor(
            ticker = "ORCL",
            baselinePrice = 212.45,
            currentPrice = 212.10,
            strategyLabel = "EARNINGS VOLATILITY FLANK",
            putStrikes = "$195 / $200",
            callStrikes = "$225 / $230",
            initialCredit = 1.85,
            currentMidPrice = 1.79,
            targetGtcClose = 0.92,
            earningsDate = "June 10 (After Close)",
            hasCatalystTodayOrTomorrow = true
        )
    )

    Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "System: ${if (isAutomationActive) "Operational" else "Offline"}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isAutomationActive) RobinhoodGreen else AlertOrange
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))

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
                                contentDescription = "Shield Logo",
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
                            Text(
                                text = if (isAutomationActive) "● Online" else "● Offline",
                                fontSize = 12.sp,
                                color = if (isAutomationActive) RobinhoodGreen else TextGray
                            )
                        }
                    }
                    Text("v1.1", color = TextGray, fontSize = 12.sp)
                }
            }

            // GLOBAL RISK PROTECTION ENGINE INTERCEPT BAR
            val activeCatalysts = portfolioPositions.filter { it.hasCatalystTodayOrTomorrow }
            if (activeCatalysts.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, AlertOrange, RoundedCornerShape(10.dp))
                            .background(DarkOrangeBg, shape = RoundedCornerShape(10.dp))
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Warning Alert",
                                tint = AlertOrange,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "PENDING CATALYST LOCKOUT",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = AlertOrange
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Positions on ${activeCatalysts.joinToString { it.ticker }} have major corporate earnings reports within the next 36 hours. Hard exit recommended before 4:00 PM ET to neutralize IV expansion risk.",
                            fontSize = 11.sp,
                            color = TextWhite,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderGreen, RoundedCornerShape(10.dp))
                        .background(SurfaceCard, shape = RoundedCornerShape(10.dp))
                        .padding(14.dp)
                ) {
                    Text("PORTFOLIO NET BALANCE", fontSize = 11.sp, color = TextWhite, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$17,821.97", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("(+$400.35 Liquid)", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = RobinhoodGreen)
                    }
                }
            }

            item {
                Text("MONITORED PREMIUM SPREADS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextWhite)
            }

            items(portfolioPositions) { trade ->
                CondorPositionCard(trade)
            }
        }
    }
}

@Composable
fun CondorPositionCard(trade: TrackedCondor) {
    val maximumExtractedPoints = trade.initialCredit - trade.targetGtcClose
    val currentPointsHarvested = (trade.initialCredit - trade.currentMidPrice).coerceAtLeast(0.0)
    val percentageDecayed = (currentPointsHarvested / maximumExtractedPoints).coerceIn(0.0, 1.0).toFloat()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (trade.hasCatalystTodayOrTomorrow) AlertOrange else Color(0xFF2C2C2C),
                shape = RoundedCornerShape(12.dp)
            )
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
                    Text(text = trade.ticker, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                    if (trade.hasCatalystTodayOrTomorrow) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = AlertOrange.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "EARNINGS CATALYST",
                                color = AlertOrange,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(text = trade.strategyLabel, fontSize = 10.sp, color = TextGray, fontWeight = FontWeight.Bold)
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Transparent,
                modifier = Modifier.border(1.dp, if (trade.hasCatalystTodayOrTomorrow) AlertOrange else RobinhoodGreen, RoundedCornerShape(6.dp))
            ) {
                Text(
                    text = if (trade.hasCatalystTodayOrTomorrow) "CATALYST ALERT" else "GTC close",
                    color = if (trade.hasCatalystTodayOrTomorrow) AlertOrange else RobinhoodGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LinearProgressIndicator(
            progress = { percentageDecayed },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = if (trade.hasCatalystTodayOrTomorrow) AlertOrange else RobinhoodGreen,
            trackColor = Color(0xFF2A2A2A),
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "Earnings Event Windows:", fontSize = 11.sp, color = TextGray)
                Text(text = trade.earningsDate, fontSize = 12.sp, color = TextWhite, fontWeight = FontWeight.Medium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "Premium mid-price", fontSize = 11.sp, color = TextGray)
                Text(
                    text = String.format("$%.2f", trade.currentMidPrice),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (trade.hasCatalystTodayOrTomorrow) AlertOrange else TextWhite
                )
            }
        }
    }
}

@Composable
fun SettingsTerminalTab(
    alpacaKey: String, onAlpacaKeyChange: (String) -> Unit,
    alpacaSecret: String, onAlpacaSecretChange: (String) -> Unit,
    geminiKey: String, onGeminiKeyChange: (String) -> Unit,
    aiStudioKey: String, onAiStudioKeyChange: (String) -> Unit,
    isAutomationActive: Boolean, onAutomationToggle: (Boolean) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("← Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isAutomationActive) RobinhoodGreen.copy(alpha = 0.15f) else Color(0xFF2A2A2A)
                ) {
                    Text(
                        text = if (isAutomationActive) "✓ Connected" else "Disconnected",
                        color = if (isAutomationActive) RobinhoodGreen else TextGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF262626))

            TerminalInputField(label = "Alpaca API Key", value = alpacaKey, onValueChange = onAlpacaKeyChange)
            TerminalInputField(label = "Alpaca API Secret", value = alpacaSecret, onValueChange = onAlpacaSecretChange, isProtected = true)
            TerminalInputField(label = "Google Gemini API Key", value = geminiKey, onValueChange = onGeminiKeyChange, isProtected = true)
            TerminalInputField(label = "Google AI Studio Key", value = aiStudioKey, onValueChange = onAiStudioKeyChange, isProtected = true)

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Full Automation", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextWhite)
                Switch(
                    checked = isAutomationActive,
                    onCheckedChange = onAutomationToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextWhite,
                        checkedTrackColor = RobinhoodGreen,
                        uncheckedThumbColor = TextGray,
                        uncheckedTrackColor = Color(0xFF333333)
                    )
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { /* Re-sync credentials to device storage */ },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RobinhoodGreen)
            ) {
                Text("Save and Connect", color = DarkBackground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
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
