package com.fortress.trader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val FortressDarkColors = darkColorScheme(
    primary = FortressGreen,
    onPrimary = FortressBlack,
    secondary = FortressOrange,
    onSecondary = FortressBlack,
    background = FortressBlack,
    onBackground = TextPrimary,
    surface = FortressSurface,
    onSurface = TextPrimary,
    surfaceVariant = FortressSurface,
    onSurfaceVariant = TextSecondary,
    outline = FortressBorder,
    error = FortressRed,
    onError = FortressBlack
)

@Composable
fun FortressTraderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FortressDarkColors,
        typography = FortressTypography,
        content = content
    )
}
