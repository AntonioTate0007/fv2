package com.fortress.trader.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector

/** Bottom-navigation destinations for the Fortress Trader app. */
enum class TraderDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Dashboard("dashboard", "Dashboard", Icons.Default.PlayArrow),
    Portfolio("portfolio", "Portfolio", Icons.Default.BarChart),
    Risk("risk", "Risk", Icons.Default.Warning),
    Settings("settings", "Settings", Icons.Default.Settings);

    companion object {
        val All = entries.toList()
        val Start = Dashboard
    }
}
