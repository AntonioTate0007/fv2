package com.thumbshade.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
enum class GestureType(val label: String) {
    NONE("Nothing"),
    TOGGLE_SHADE("Open / close the shade"),
    OPEN_LATEST("Open the newest notification"),
    OPEN_APP("Open an app"),
    BACK("Back"),
    HOME("Home"),
    RECENTS("Recent apps"),
    LAST_APP("Previous app"),
    NOTIFICATIONS("System notification shade"),
    QUICK_SETTINGS("Quick settings"),
    ASSISTANT("Assistant"),
    TORCH("Toggle torch"),
    TOGGLE_MUTE("Toggle mute"),
    PASTE_TEXT("Paste a saved text"),
    SCREENSHOT("Screenshot"),
    LOCK_SCREEN("Lock screen"),
    HIDE_BUTTON("Hide the button for a while"),
    CLEAR_ALL("Clear all notifications"),
    RELEASE_HELD("Release held notifications"),
    NEXT_MODE("Switch to the next mode"),
    MEDIA_PLAY_PAUSE("Play / pause media"),
    MEDIA_NEXT("Next track"),
}

@Serializable
data class GestureAction(val type: GestureType = GestureType.NONE, val arg: String = "")

@Serializable
data class GestureMode(
    val name: String = "Home",
    val tap: GestureAction = GestureAction(GestureType.TOGGLE_SHADE),
    val up: GestureAction = GestureAction(GestureType.OPEN_LATEST),
    val down: GestureAction = GestureAction(GestureType.HIDE_BUTTON),
    val left: GestureAction = GestureAction(GestureType.BACK),
    val right: GestureAction = GestureAction(GestureType.LAST_APP),
)

@Serializable
enum class KeyboardBehavior(val label: String) { NOTHING("Stay put"), MOVE_ABOVE("Move above the keyboard"), HIDE("Hide while typing") }

@Serializable
enum class ShadeAnim(val label: String) { SLIDE("Slide"), FADE("Fade"), SCALE("Zoom"), EXPAND("Unfold"), BOUNCE("Bounce"), DROP("Drop in") }

@Serializable
enum class BrowseStyle(val label: String) { LIST("List"), WHEEL("Ferris wheel") }

@Serializable
enum class EdgeStyle(val label: String, val aroundButton: Boolean = false) {
    BASIC("Basic"),
    MULTICOLOUR("Multicolour"),
    GLOW("Glow"),
    HEARTBEAT("Heartbeat"),
    NEON("Neon"),
    COMET("Comet"),
    RIPPLE("Ripple (button)", true),
    SONAR("Sonar (button)", true),
    HALO("Halo (button)", true),
}

@Serializable
enum class EdgeColorMode(val label: String) { NOTIFICATION("Notification colour"), ACCENT("Theme accent"), CUSTOM("Custom") }

@Serializable
enum class ClusterSide(val label: String) { RING("Ring around"), ABOVE("Above"), SIDE("Beside") }

@Serializable
data class AppSettings(
    // General
    val serviceEnabled: Boolean = true,
    val showButton: Boolean = true,
    val dynamicColor: Boolean = false,
    val autoTheme: Boolean = true,
    val activeThemeId: String = "builtin:dark",
    val lightThemeId: String = "builtin:light",
    val darkThemeId: String = "builtin:dark",
    val customThemes: List<ThemeDef> = emptyList(),
    val tabBarBottom: Boolean = true,
    val notificationControls: Boolean = true,
    val hideOverlayWarning: Boolean = true,
    val lockscreenShade: Boolean = true,
    val openShadeIcon: Boolean = false,
    val savedTexts: List<String> = emptyList(),

    // Notifications
    val ungroup: Boolean = true,
    val hideNoTime: Boolean = false,
    val hideOngoing: Boolean = false,
    val applyDnd: Boolean = false,
    val includeApps: Set<String> = emptySet(),
    val excludeApps: Set<String> = emptySet(),
    val pinTop: Set<String> = emptySet(),
    val pinBottom: Set<String> = emptySet(),
    val groupApps: Set<String> = emptySet(),
    val textFilter: Boolean = false,
    val showOnlyWords: String = "",
    val hideWords: String = "",
    val perAppColor: Map<String, Long> = emptyMap(),
    val perAppLines: Map<String, Int> = emptyMap(),
    val customSnoozeMinutes: Int = 180,

    // Button
    val buttonWidthDp: Int = 56,
    val buttonHeightDp: Int = 56,
    val buttonCornerPercent: Int = 50,
    val buttonColor: Long = 0xE6202124,
    val buttonBorderColor: Long = 0xFFE0E0E0,
    val buttonBorderDp: Int = 2,
    val buttonAlpha: Float = 1f,
    val showCount: Boolean = true,
    val showLatestIcon: Boolean = false,
    val showAlbumArt: Boolean = true,
    val iconCluster: Boolean = true,
    val clusterSide: ClusterSide = ClusterSide.RING,
    val clusterMax: Int = 7,
    val clusterIconDp: Int = 30,
    val monochromeIcons: Boolean = false,
    val snapToEdge: Boolean = true,
    val hideWhenEmpty: Boolean = false,
    val keyboardBehavior: KeyboardBehavior = KeyboardBehavior.MOVE_ABOVE,
    val hideInApps: Set<String> = emptySet(),
    val hideSeconds: Int = 10,
    val chargingRing: Boolean = true,
    val buttonX: Int = -1,
    val buttonY: Int = -1,
    val modes: List<GestureMode> = listOf(GestureMode()),
    val activeMode: Int = 0,

    // Shade
    val shadeMaxHeight: Float = 0.85f,
    val shadeWidth: Float = 1f,
    val newestAtBottom: Boolean = true,
    val dimBehind: Float = 0.55f,
    val browseStyle: BrowseStyle = BrowseStyle.LIST,
    val shadeAnim: ShadeAnim = ShadeAnim.SLIDE,
    val cardCornerDp: Int = 24,
    val cardColor: Long = 0,
    val bodyMaxLines: Int = 4,
    val showLargeIcon: Boolean = true,
    val showPictures: Boolean = true,
    val showActions: Boolean = true,
    val showMedia: Boolean = true,
    val albumArtBackground: Boolean = true,
    val swipeToDismiss: Boolean = true,
    val closeAfterOpen: Boolean = true,
    val closeWhenEmpty: Boolean = true,

    // Screen lighting
    val edgeLight: Boolean = false,
    val edgeStyle: EdgeStyle = EdgeStyle.GLOW,
    val buttonEffect: EdgeStyle? = EdgeStyle.RIPPLE,
    val edgeColorMode: EdgeColorMode = EdgeColorMode.NOTIFICATION,
    val edgeCustomColor: Long = 0xFF7C4DFF,
    val edgeDurationMs: Int = 3000,
    val edgeThicknessDp: Int = 6,
    val edgeWakeScreen: Boolean = false,
    val edgeOnlyScreenOn: Boolean = false,
)

@Serializable
data class Backup(
    val version: Int = 1,
    val settings: AppSettings,
    val rules: List<com.thumbshade.app.rules.Rule>,
)
