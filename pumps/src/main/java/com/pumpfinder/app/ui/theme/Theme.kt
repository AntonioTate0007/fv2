package com.pumpfinder.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PumpsDarkColors = darkColorScheme(
    primary = OrangeHot,
    onPrimary = TextMain,
    secondary = GoldHot,
    onSecondary = Bg,
    background = Bg,
    onBackground = TextMain,
    surface = Panel,
    onSurface = TextMain,
    surfaceVariant = Panel2,
    onSurfaceVariant = TextDim,
    outline = Line,
    error = RedBad,
    onError = TextMain,
)

@Composable
fun PumpsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PumpsDarkColors,
        typography = PumpsTypography,
        content = content,
    )
}
