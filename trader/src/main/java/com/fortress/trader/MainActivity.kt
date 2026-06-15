package com.fortress.trader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fortress.trader.ui.navigation.TraderDestination
import com.fortress.trader.ui.screens.DashboardScreen
import com.fortress.trader.ui.screens.PortfolioScreen
import com.fortress.trader.ui.screens.RiskScreen
import com.fortress.trader.ui.screens.SettingsScreen
import com.fortress.trader.ui.theme.FortressBorder
import com.fortress.trader.ui.theme.FortressGreen
import com.fortress.trader.ui.theme.FortressSurface
import com.fortress.trader.ui.theme.FortressTraderTheme
import com.fortress.trader.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FortressTraderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FortressTraderApp()
                }
            }
        }
    }
}

@Composable
private fun FortressTraderApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = FortressSurface) {
                TraderDestination.All.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            if (currentRoute == dest.route) return@NavigationBarItem
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = FortressGreen,
                            unselectedIconColor = TextSecondary,
                            selectedTextColor = FortressGreen,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = FortressBorder
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TraderDestination.Start.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(TraderDestination.Dashboard.route) { DashboardScreen(contentPadding = padding) }
            composable(TraderDestination.Portfolio.route) { PortfolioScreen(contentPadding = padding) }
            composable(TraderDestination.Risk.route) { RiskScreen(contentPadding = padding) }
            composable(TraderDestination.Settings.route) { SettingsScreen(contentPadding = padding) }
        }
    }
}
