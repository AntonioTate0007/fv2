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
import com.thumbshade.app.data.ThemeDef
import com.thumbshade.app.data.Themes
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.thumbshade.app.notif.AppInfoCache
import java.util.concurrent.ConcurrentHashMap

private val bitmapCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun AppIcon(pkg: String, modifier: Modifier = Modifier, colorFilter: ColorFilter? = null) {
    val context = LocalContext.current
    val iconVersion by com.thumbshade.app.icons.IconStore.version.collectAsState()
    val bmp = remember(pkg, iconVersion) {
        val key = "$iconVersion:$pkg"
        bitmapCache[key] ?: AppInfoCache.icon(context, pkg)?.let { d ->
            runCatching { d.toBitmap(128, 128).asImageBitmap() }.getOrNull()
        }?.also { bitmapCache[key] = it }
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

/** The accent colour in effect, for code outside Compose (button ring, screen lighting). */
fun currentAccent(context: android.content.Context, s: com.thumbshade.app.data.AppSettings): Long {
    val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    if (s.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        val scheme = if (night) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        return scheme.primary.toArgb().toLong() and 0xFFFFFFFFL
    }
    return Themes.resolve(s, night).accent
}

/** Material colours built from one of our themes. */
fun ThemeDef.toColorScheme(): ColorScheme {
    val accent = Color(accent)
    val bg = Color(background)
    val card = Color(card)
    val text = Color(text)
    val secondary = Color(secondaryText)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accent.copy(alpha = 0.25f).compositeOver(card),
        onPrimaryContainer = text,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accent.copy(alpha = 0.18f).compositeOver(card),
        onSecondaryContainer = text,
        tertiary = accent,
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceVariant = card,
        onSurfaceVariant = secondary,
        surfaceContainerLowest = bg,
        surfaceContainerLow = card.copy(alpha = 0.6f).compositeOver(bg),
        surfaceContainer = card,
        surfaceContainerHigh = text.copy(alpha = 0.06f).compositeOver(card),
        surfaceContainerHighest = text.copy(alpha = 0.1f).compositeOver(card),
        outline = secondary.copy(alpha = 0.6f),
        outlineVariant = secondary.copy(alpha = 0.3f),
    )
}

@Composable
fun ThumbTheme(content: @Composable () -> Unit) {
    val s by SettingsRepo.state.collectAsState()
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val scheme: ColorScheme = if (s.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        // Material You: light or dark follows the phone when Auto is on, otherwise the chosen theme.
        val dark = if (s.autoTheme) systemDark else Themes.resolve(s, systemDark).dark
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        Themes.resolve(s, systemDark).toColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
