package com.pumpfinder.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val PumpsTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 28.sp, letterSpacing = 0.4.sp),
    headlineMedium= TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 0.3.sp),
    titleLarge    = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium   = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge     = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 15.sp),
    bodyMedium    = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 13.sp),
    labelLarge    = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 13.sp, letterSpacing = 0.6.sp),
    labelMedium   = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 11.sp, letterSpacing = 0.6.sp),
)
