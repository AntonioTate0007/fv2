package com.fortress.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val AutopilotLightColors = lightColorScheme(
    primary = FortressBlack,
    onPrimary = FortressWhite,
    secondary = ProfitGreen,
    onSecondary = FortressWhite,
    background = FortressWhite,
    onBackground = TextPrimary,
    surface = FortressWhite,
    onSurface = TextPrimary,
    surfaceVariant = FortressOffWhite,
    onSurfaceVariant = TextSecondary,
    outline = FortressBorder,
    error = RiskRed,
    onError = FortressWhite
)

private val AutopilotDarkColors = darkColorScheme(
    primary = FortressWhite,
    onPrimary = Color(0xFF0B0B0C),
    secondary = Color(0xFF22C77E),
    background = Color(0xFF0B0B0C),
    surface = Color(0xFF161719),
    onSurface = Color(0xFFF5F6F8),
    surfaceVariant = Color(0xFF202225),
    onSurfaceVariant = Color(0xFFA7AEBA),
    outline = Color(0xFF2C2F33),
    error = Color(0xFFFF5A4D)
)

@Composable
fun FortressTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) AutopilotDarkColors else AutopilotLightColors
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalAppColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FortressTypography,
            content = content
        )
    }
}
