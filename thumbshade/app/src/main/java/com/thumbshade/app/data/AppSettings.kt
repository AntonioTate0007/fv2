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
    APP_SCREEN("Open an app screen"),
    SHORTCUT("Run a shortcut"),
    CUSTOM_INTENT("Send a custom intent"),
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
data class GestureAction(
    val type: GestureType = GestureType.NONE,
    val arg: String = "",
    /** What to call it in the settings, for shortcuts and intents. */
    val label: String = "",
)

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
enum class KeyboardBehavior(val label: String) {
    NOTHING("Work as usual"),
    MOVE_ABOVE("Move above the keyboard"),
    HIDE("Hide while the keyboard is open"),
    DOCK("Dock to the side while typing"),
    CLICK_THROUGH("Click through (taps go to the keyboard)"),
}

/** What the button does in the apps you pick. */
@Serializable
enum class AppBehavior(val label: String) {
    NOTHING("Work as usual"),
    HIDE("Hide while the app is open"),
    DOCK("Dock to the side"),
    CLICK_THROUGH("Click through (taps go to the app)"),
}

@Serializable
enum class EmptyBehavior(val label: String) { STAY("Stay visible"), HIDE("Hide the button"), DOCK("Dock to the side") }

@Serializable
enum class LandscapeBehavior(val label: String) { RELATIVE("Relative position"), KEEP("Keep in the same place"), HIDE("Do not show") }

/** Docked = snapped against the left or right edge. */
@Serializable
enum class SnapStyle(val label: String) { HALF("Half tucked behind the edge"), FULL("Fully on screen, flush with the edge") }


@Serializable
enum class NotifAnim(val label: String) { NONE("None"), POP("Pop"), HOP("Hop"), WIGGLE("Wiggle"), GLOW("Glow flash") }

@Serializable
enum class ColorSource(val label: String) { THEME("Theme colour"), CUSTOM("Custom colour"), NOTIFICATION("Latest notification colour"), NONE("None") }

@Serializable
enum class NumberAlign(val label: String) { CENTER("Centre"), TOP_START("Top left"), TOP_END("Top right"), BOTTOM_START("Bottom left"), BOTTOM_END("Bottom right") }

@Serializable
enum class MediaLook(val label: String) {
    NOTHING("Nothing"), ALBUM_ART("Album art"), RECORD("Record"), CD("CD"), TAPE("Tape"),
    EQUALIZER("Equalizer"), PULSE("Pulse"), WAVE("Wave"), NOTES("Music notes"), TICKER("Scrolling title"),
}

@Serializable
enum class AnimatedIcon(val label: String) {
    NONE("None"), RIM_LIGHT("Rim light"), MESH_ORB("Mesh orb"), BATTERY_DOTS("Battery dots"),
    OCEAN_WAVE("Ocean wave"), RADAR("Radar"), RIPPLE("Ripple"), FIREFLIES("Fireflies"),
    SPECTRUM("Spectrum"), SHIMMER("Shimmer"), CONSTELLATION("Constellation"), HEARTBEAT("Heartbeat"),
    STARFIELD("Starfield"), SHAPE_MORPH("Shape morph"), ORBIT("Orbit"), CLOCK("Clock"),
    BATTERY_RING("Battery ring"), AURORA("Aurora"),
}

@Serializable
enum class ChargingMode(val label: String) { NONE("None"), RING("Ring"), PROGRESS("Progress ring (battery level)") }

@Serializable
enum class ChargingAnim(val label: String) { NONE("None"), SWEEP("Sweep"), BREATHE("Breathe") }

@Serializable
enum class ShadeAlign(val label: String) { LEFT("Left"), CENTER("Centre"), RIGHT("Right") }

@Serializable
enum class ShadeAnim(val label: String) {
    SLIDE("Slide"), FADE("Fade"), SCALE("Scale"), EXPAND("Unfold"), BOUNCE("Bounce"), DROP("Drop in"),
    NONE("None (instant)"), ZOOM("Zoom"), POP("Pop"), ELASTIC("Elastic"), GLIDE("Glide from the right"),
    GLIDE_LEFT("Glide from the left"), RISE("Rise"), FALL("Fall from above"), FLIP("Flip up"),
    FLIP_SIDE("Flip sideways"), PAPER("Paper fold"), DOOR("Door (left hinge)"), DOOR_RIGHT("Door (right hinge)"),
    SWING("Swing"), SWING_RIGHT("Swing (right)"), SPIN("Spin"), TWIRL("Twirl"), TUMBLE("Tumble"),
    CURTAIN("Curtain"), BLINDS("Blinds"), CORNER_LEFT("Grow from the left corner"),
    CORNER_RIGHT("Grow from the right corner"), TILT("Tilt"), SWOOP("Swoop"), SQUASH("Squash"),
    STRETCH("Stretch"), WOBBLE("Wobble"), JELLY("Jelly"), SLINGSHOT("Slingshot"), GROW("Grow"),
    SHRINK("Shrink into place"), DEAL("Deal (like a card)"),
}

@Serializable
enum class BrowseStyle(val label: String) {
    LIST("List"),
    WHEEL("Ferris wheel (cards curve away in depth)"),
    COVERFLOW("Coverflow (cards tilt back in 3D)"),
    SPOTLIGHT("Spotlight (centre card stands out)"),
    FAN("Fan (cards splay like a hand of cards)"),
    WAVE("Wave (cards weave side to side)"),
    CASCADE("Cascade (cards step sideways)"),
    SWAY("Sway (cards swing like hanging signs)"),
    TUMBLE("Tumble (cards cartwheel away)"),
    HELIX("Helix (cards turn on a corkscrew)"),
    CARD_STACK("Card stack (focused card on top, others stacked behind)"),
    BOOK("Book (cards turn like pages)"),
    CONVEYOR("Conveyor (cards ride a slanted belt)"),
    CRESCENT("Crescent (cards follow a curve)"),
    FLYTHROUGH("Fly-through (cards grow as they come closer)"),
    LENS("Lens (the middle card is magnified)"),
    ORIGAMI("Origami (cards fold in alternate directions)"),
    PINCH("Pinch (cards narrow towards the ends)"),
    SWIRL("Swirl (cards swirl round the middle)"),
    SWIVEL("Swivel (cards turn on their left edge)"),
}

@Serializable
enum class ShadeOverlay(val label: String) { NONE("None"), DIM("Dim"), BLUR("Blur"), DIM_BLUR("Dim and blur") }

@Serializable
enum class EdgeStyle(val label: String, val aroundButton: Boolean = false) {
    BASIC("Basic"),
    MULTICOLOUR("Multicolour"),
    GLOW("Glow"),
    ECHO("Echo"),
    NEON("Neon"),
    LIGHTNING("Lightning"),
    RISE("Rise"),
    HEARTBEAT("Heartbeat"),
    DRIP("Drip"),
    CONVERGE("Converge"),
    COMET("Comet"),
    WAVE("Wave", true),
    BUBBLES("Bubbles", true),
    FIREWORKS("Fireworks", true),
    ECLIPSE("Eclipse", true),
    SPOTLIGHT("Spotlight", true),
    HALO("Halo", true),
    RIPPLE("Ripple", true),
    SPARKLE("Sparkle", true),
    PULSE_RINGS("Pulse rings", true),
    CHARGE("Charge", true),
    VORTEX("Vortex", true),
    SONAR("Sonar", true),
    CONFETTI("Confetti", true),
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
    val perCorner: Boolean = false,
    val cornerTopLeftDp: Int = 28,
    val cornerTopRightDp: Int = 28,
    val cornerBottomLeftDp: Int = 28,
    val cornerBottomRightDp: Int = 28,
    val buttonBgSource: ColorSource = ColorSource.CUSTOM,
    val buttonBorderSource: ColorSource = ColorSource.CUSTOM,
    val buttonColor: Long = 0xE6202124,
    val buttonBorderColor: Long = 0xFFE0E0E0,
    val buttonBorderDp: Int = 2,
    val buttonAlpha: Float = 1f,
    val showCount: Boolean = true,
    val numberSizeSp: Int = 18,
    val numberBold: Boolean = true,
    val numberColor: Long = 0xFFFFFFFF,
    val numberHideSingle: Boolean = false,
    val numberAlign: NumberAlign = NumberAlign.CENTER,
    val showLatestIcon: Boolean = false,
    val mediaLook: MediaLook = MediaLook.ALBUM_ART,
    val mediaOnlyPlaying: Boolean = true,
    val mediaDimPercent: Int = 0,
    val mediaAnimColor: Long = 0xFF4DD9C9,
    val appearAnim: ShadeAnim = ShadeAnim.POP,
    val animatedIcon: AnimatedIcon = AnimatedIcon.NONE,
    /** 0 = the button's accent colour. */
    val animatedIconColor: Long = 0,
    val newNotifAnim: NotifAnim = NotifAnim.POP,
    val newNotifIntensity: Int = 60,
    val iconCluster: Boolean = true,
    val clusterSide: ClusterSide = ClusterSide.RING,
    val clusterMax: Int = 7,
    val clusterIconDp: Int = 30,
    val monochromeIcons: Boolean = false,
    val snapToEdge: Boolean = true,
    val snapStyle: SnapStyle = SnapStyle.HALF,
    val snapZonePercent: Int = 25,
    val flingVelocityDp: Int = 800,
    val allowOffscreen: Boolean = false,
    val dockedLook: Boolean = false,
    val dockedWidthDp: Int = 44,
    val dockedHeightDp: Int = 72,
    val dockedCornerPercent: Int = 50,
    val dockedColor: Long = 0xCC202124,
    val dockedAlpha: Float = 0.85f,
    val emptyBehavior: EmptyBehavior = EmptyBehavior.STAY,
    val keyboardBehavior: KeyboardBehavior = KeyboardBehavior.MOVE_ABOVE,
    val hideInApps: Set<String> = emptySet(),
    val appBehavior: AppBehavior = AppBehavior.HIDE,
    val landscapeBehavior: LandscapeBehavior = LandscapeBehavior.RELATIVE,
    val hideSeconds: Int = 10,
    val chargingMode: ChargingMode = ChargingMode.PROGRESS,
    val chargingThicknessDp: Int = 3,
    val chargingAnim: ChargingAnim = ChargingAnim.SWEEP,
    /** Saved position as fractions of the portrait screen (-1 = default). */
    val buttonXFrac: Float = -1f,
    val buttonYFrac: Float = -1f,
    val buttonDocked: Boolean = true,
    val dockRight: Boolean = true,
    val modes: List<GestureMode> = listOf(GestureMode()),
    val activeMode: Int = 0,

    // Shade
    val shadeMaxHeight: Float = 0.85f,
    val shadeWidth: Float = 1f,
    val shadeAlign: ShadeAlign = ShadeAlign.CENTER,
    val card: CardStyle = CardStyle(),
    val rowSpacingDp: Int = 8,
    val newestAtBottom: Boolean = true,
    val dimBehind: Float = 0.55f,
    val shadeOverlay: ShadeOverlay = ShadeOverlay.DIM,
    val blurRadiusDp: Int = 24,
    val rememberScroll: Boolean = false,
    val pushPullClose: Boolean = false,
    val pullCloseDp: Int = 90,
    val wrapAround: Boolean = false,
    val swipeDismissFraction: Float = 0.4f,
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
