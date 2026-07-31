package com.fortress.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class FortressDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Home(route = "home", label = "Home", icon = Icons.Filled.Home),
    Discover(route = "discover", label = "Discover", icon = Icons.Filled.Explore),
    Activity(route = "activity", label = "Activity", icon = Icons.Filled.Notifications),
    Settings(route = "settings", label = "Settings", icon = Icons.Filled.Settings);

    companion object {
        val Start = Home
        val All = entries.toList()
    }
}
