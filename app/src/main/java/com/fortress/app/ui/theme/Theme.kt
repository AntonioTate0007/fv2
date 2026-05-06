package com.fortress.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val FortressLightColors = lightColorScheme(
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

@Composable
fun FortressTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FortressLightColors,
        typography = FortressTypography,
        content = content
    )
}
