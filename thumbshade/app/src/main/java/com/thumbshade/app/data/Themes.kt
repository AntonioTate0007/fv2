package com.thumbshade.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

/** A colour theme for the app and the shade. Colours are ARGB. */
@Serializable
data class ThemeDef(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "My theme",
    val dark: Boolean = true,
    val accent: Long = 0xFF4DD9C9,
    val background: Long = 0xFF121417,
    val card: Long = 0xFF1E2226,
    val text: Long = 0xFFF1F3F4,
    val secondaryText: Long = 0xFFB0B6BC,
) {
    val isBuiltIn: Boolean get() = id.startsWith(BUILT_IN_PREFIX)

    /** The colours shown as swatches in pickers. */
    val swatches: List<Long> get() = listOf(accent, background, card, text, secondaryText)

    companion object {
        const val BUILT_IN_PREFIX = "builtin:"
    }
}

object Themes {
    val dark = ThemeDef("builtin:dark", "Dark", true, 0xFF4DD9C9, 0xFF121417, 0xFF1E2226, 0xFFF1F3F4, 0xFFB0B6BC)
    val light = ThemeDef("builtin:light", "Light", false, 0xFF00897B, 0xFFF4F7F7, 0xFFFFFFFF, 0xFF1A1C1E, 0xFF5F6368)
    val black = ThemeDef("builtin:black", "Pure black", true, 0xFF4DD9C9, 0xFF000000, 0xFF111111, 0xFFFFFFFF, 0xFFA0A0A0)
    val ocean = ThemeDef("builtin:ocean", "Ocean", true, 0xFF4FC3F7, 0xFF0B1A2A, 0xFF13283D, 0xFFE3F2FD, 0xFF90A4AE)
    val forest = ThemeDef("builtin:forest", "Forest", true, 0xFF81C784, 0xFF0F1A12, 0xFF1A2A1E, 0xFFE8F5E9, 0xFFA5B8A8)
    val sunset = ThemeDef("builtin:sunset", "Sunset", true, 0xFFFF8A65, 0xFF1F1214, 0xFF2E1B1F, 0xFFFFEBEE, 0xFFC7A6A8)
    val grape = ThemeDef("builtin:grape", "Grape", true, 0xFFB388FF, 0xFF16121F, 0xFF231C31, 0xFFEDE7F6, 0xFFB3A9C6)
    val paper = ThemeDef("builtin:paper", "Paper", false, 0xFF6D4C41, 0xFFFAF6F0, 0xFFFFFFFF, 0xFF3E2723, 0xFF795548)
    val rose = ThemeDef("builtin:rose", "Rose", false, 0xFFD81B60, 0xFFFFF5F7, 0xFFFFFFFF, 0xFF311B22, 0xFF7B5A63)

    val builtIn: List<ThemeDef> = listOf(dark, light, black, ocean, forest, sunset, grape, paper, rose)

    fun all(s: AppSettings): List<ThemeDef> = builtIn + s.customThemes

    fun byId(s: AppSettings, id: String): ThemeDef? = all(s).firstOrNull { it.id == id }

    /**
     * The theme in effect. With "Auto (follow system)" on, the light or dark choice follows the
     * phone's dark mode; otherwise the selected theme is used.
     */
    fun resolve(s: AppSettings, systemDark: Boolean): ThemeDef =
        if (s.autoTheme) {
            if (systemDark) byId(s, s.darkThemeId) ?: dark else byId(s, s.lightThemeId) ?: light
        } else {
            byId(s, s.activeThemeId) ?: dark
        }
}
