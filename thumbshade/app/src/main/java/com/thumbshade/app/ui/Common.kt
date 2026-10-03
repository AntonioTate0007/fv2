package com.thumbshade.app.ui

import android.graphics.drawable.Icon
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.data.ThemeMode
import com.thumbshade.app.notif.AppInfoCache
import java.util.concurrent.ConcurrentHashMap

private val bitmapCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun AppIcon(pkg: String, modifier: Modifier = Modifier, colorFilter: ColorFilter? = null) {
    val context = LocalContext.current
    val bmp = remember(pkg) {
        bitmapCache[pkg] ?: AppInfoCache.icon(context, pkg)?.let { d ->
            runCatching { d.toBitmap(128, 128).asImageBitmap() }.getOrNull()
        }?.also { bitmapCache[pkg] = it }
    }
    if (bmp != null) Image(bitmap = bmp, contentDescription = null, modifier = modifier, colorFilter = colorFilter)
}

/** A notification's own Icon (small or large), loaded from the posting app. */
@Composable
fun NotifIcon(icon: Icon?, cacheKey: String, modifier: Modifier = Modifier, tint: Color? = null) {
    val context = LocalContext.current
    val bmp = remember(cacheKey) {
        icon?.let { runCatching { it.loadDrawable(context)?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = modifier,
            colorFilter = tint?.let { ColorFilter.tint(it) },
        )
    }
}

fun relativeTime(time: Long): String {
    val diff = System.currentTimeMillis() - time
    return when {
        diff < 60_000 -> "now"
        diff < DateUtils.DAY_IN_MILLIS -> DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(time))
    }
}

fun clockTime(time: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(time))

@Composable
fun ThumbTheme(content: @Composable () -> Unit) {
    val s by SettingsRepo.state.collectAsState()
    val context = LocalContext.current
    val dark = when (s.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK, ThemeMode.BLACK -> true
        ThemeMode.LIGHT -> false
    }
    val accent = Color(s.accentColor)
    var scheme: ColorScheme = when {
        s.dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = accent, secondary = accent, tertiary = accent)
        else -> lightColorScheme(primary = accent, secondary = accent, tertiary = accent)
    }
    if (s.themeMode == ThemeMode.BLACK) {
        scheme = scheme.copy(background = Color.Black, surface = Color.Black, surfaceContainer = Color(0xFF111111), surfaceContainerHigh = Color(0xFF1A1A1A))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
