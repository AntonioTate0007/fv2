package com.mark39.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val MarkColors = darkColorScheme(
    primary = ReactorCyan,
    onPrimary = SpaceBlack,
    secondary = ReactorCyanSoft,
    background = SpaceBlack,
    onBackground = TextOnDark,
    surface = SpacePanel,
    onSurface = TextOnDark,
    surfaceVariant = SpacePanelSoft,
    onSurfaceVariant = TextDim
)

private val MarkTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
)

@Composable
fun Mark39Theme(content: @Composable () -> Unit) {
    // Always dark — the arc-reactor look is intentional regardless of system setting.
    MaterialTheme(colorScheme = MarkColors, typography = MarkTypography, content = content)
}
