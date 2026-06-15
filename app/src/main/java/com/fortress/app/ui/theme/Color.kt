package com.fortress.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand palette — light, ultra-minimalist (Autopilot / Robinhood-style).
val FortressBlack = Color(0xFF0B0B0C)
val FortressWhite = Color(0xFFFFFFFF)
val FortressOffWhite = Color(0xFFF5F6F8)
val FortressBorder = Color(0xFFE6E8EC)

// Autopilot accent — the blue used on the Install button / primary actions.
val AutopilotBlue = Color(0xFF1A56F0)
val AutopilotBlueSoft = Color(0xFFE8EEFF)

// Semantic colors
val ProfitGreen = Color(0xFF00B25B)
val ProfitGreenSoft = Color(0xFFE6F7EE)
val RiskRed = Color(0xFFE0382B)
val RiskRedSoft = Color(0xFFFDEBEB)

// Text
val TextPrimary = Color(0xFF0B0B0C)
val TextSecondary = Color(0xFF5B616E)
val TextTertiary = Color(0xFF9AA0AB)

// ── Theme-aware palette ─────────────────────────────────────────────────────
/** Semantic colors that flip between light and dark themes. */
data class AppPalette(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val profit: Color,
    val risk: Color
)

val LightPalette = AppPalette(
    bg = FortressWhite,
    surface = FortressWhite,
    surfaceAlt = FortressOffWhite,
    border = FortressBorder,
    textPrimary = Color(0xFF0B0B0C),
    textSecondary = Color(0xFF5B616E),
    textTertiary = Color(0xFF9AA0AB),
    accent = FortressBlack,
    onAccent = FortressWhite,
    profit = ProfitGreen,
    risk = RiskRed
)

val DarkPalette = AppPalette(
    bg = Color(0xFF0B0B0C),
    surface = Color(0xFF161719),
    surfaceAlt = Color(0xFF202225),
    border = Color(0xFF2C2F33),
    textPrimary = Color(0xFFF5F6F8),
    textSecondary = Color(0xFFA7AEBA),
    textTertiary = Color(0xFF6B7280),
    accent = FortressWhite,
    onAccent = Color(0xFF0B0B0C),
    profit = Color(0xFF22C77E),
    risk = Color(0xFFFF5A4D)
)

val LocalAppColors = staticCompositionLocalOf { LightPalette }

val appColors: AppPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalAppColors.current
