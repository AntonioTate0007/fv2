package com.fortress.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.ui.graphics.vector.ImageVector

enum class FortressDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Jarvis(route = "jarvis", label = "Jarvis", icon = Icons.Filled.AutoAwesome),
    Radar(route = "radar", label = "Radar", icon = Icons.Filled.Radar),
    Armory(route = "armory", label = "Armory", icon = Icons.Filled.Shield),
    Officer(route = "officer", label = "Officer", icon = Icons.AutoMirrored.Filled.Chat),
    Settings(route = "settings", label = "Settings", icon = Icons.Filled.Settings);

    companion object {
        val Start = Jarvis
        val All = values().toList()
    }
}
