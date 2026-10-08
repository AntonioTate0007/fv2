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
    OPEN_APP_NOTIFICATION("Open an app's newest notification"),
    QUICK_TEXT("Text a favourite contact"),
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
    /** A quick double tap (without holding). NONE = same as a tap. */
    val doubleTap: GestureAction = GestureAction(),
    val up: GestureAction = GestureAction(GestureType.OPEN_LATEST),
    val down: GestureAction = GestureAction(GestureType.HIDE_BUTTON),
    val left: GestureAction = GestureAction(GestureType.BACK),
    val right: GestureAction = GestureAction(GestureType.LAST_APP),
    /** Press and slide opens a wheel of actions instead of the four swipes. */
    val wheel: Boolean = false,
    /** Slots of the wheel: 6 in the inner ring, 8 in the middle, 10 in the outer. */
    val wheelSlots: List<GestureAction> = emptyList(),
) {
    fun slot(i: Int): GestureAction = wheelSlots.getOrNull(i) ?: GestureAction()

    companion object {
        val RINGS = listOf(6, 8, 10)
        val SLOT_COUNT = RINGS.sum()
    }
}

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
enum class MoveGesture(val label: String) {
    SUPER_LONG_HOLD("Super long hold"),
    DOUBLE_TAP_HOLD("Double-tap and hold"),
    LONG_PRESS("Long press"),
}

@Serializable
enum class SwitcherSource(val label: String) {
    RECENT_APPS("Recently used apps"),
    NOTIFICATIONS("Apps with notifications (newest first)"),
    BOTH("Smart mix: predicted, then notifications, then recent"),
    PREDICTED("Predicted for you"),
}

@Serializable
enum class QuickTextApp(val label: String) { MESSAGES("Messages (your texting app)"), WHATSAPP("WhatsApp") }

/** Someone you added for quick texts. [photo] is a contacts photo address, if any. */
@Serializable
data class QuickContact(val name: String, val number: String, val photo: String? = null)

@Serializable
enum class GlowColor(val label: String) { RED("Red"), APP("The app's own colour"), THEME("Theme colour"), CUSTOM("Custom") }

@Serializable
enum class MultiWindowMode(val label: String) { SPLIT("Split screen"), POP_UP("Pop-up window (where the phone supports it)") }

@Serializable
enum class LongPressAction(val label: String) {
    APP_SWITCHER("Open the app switcher"),
    MOVE("Move the button"),
}

@Serializable
enum class TickSound(val label: String) {
    NONE("None"), SYSTEM("System click"), TICK("Tick"), SOFT("Soft tap"), WOOD("Wood block"), POP("Pop"),
    BUBBLE("Bubble"), KEYBOARD("Keyboard"), TYPEWRITER("Typewriter"), GLASS("Glass"), RATCHET("Ratchet"),
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
enum class EdgeColorMode(val label: String) {
    NOTIFICATION("Notification colour"),
    APP_ICON("The app's icon colours"),
    ACCENT("Theme accent"),
    CUSTOM("Custom"),
}

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
    /** Reply from the lock-screen shade without unlocking first. */
    val replyOnLock: Boolean = false,
    /** On the lock screen, show only which app a notification is from. */
    val lockHideContent: Boolean = false,
    val lockDim: Float = 0.7f,
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
    /** Show the sender's picture in the middle of the button when the newest notification has one. */
    val contactPhoto: Boolean = true,
    /** A small app icon on the corner of that picture. */
    val contactPhotoBadge: Boolean = true,
    /** When docked, a message from someone with a picture pulls the button out for a moment. */
    val peekOnContact: Boolean = true,
    val peekSeconds: Int = 3,
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
    /** Fold the icons back into the button a while after a new notification. */
    val clusterFold: Boolean = true,
    val clusterFoldSeconds: Int = 6,
    /** Fold the icons right away once you've opened and closed the shade (you've seen them). */
    val clusterFoldAfterShade: Boolean = true,
    /** The icon of the app that just notified glows for a few seconds. */
    val glowNewIcon: Boolean = true,
    /** Cards that arrived since you last looked glow when the shade opens. */
    val glowNewCards: Boolean = true,
    val glowSeconds: Int = 6,
    val glowColorMode: GlowColor = GlowColor.RED,
    val glowCustomColor: Long = 0xFFFF2D2D,
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
    /** Long press and hold on the button. */
    val longPressAction: LongPressAction = LongPressAction.APP_SWITCHER,
    val switcherCount: Int = 8,
    val switcherSource: SwitcherSource = SwitcherSource.BOTH,
    /** Favourite people fold out with the switcher; picking one starts a text to them. */
    val quickTextEnabled: Boolean = true,
    val quickTextStarred: Boolean = true,
    val quickTextCount: Int = 5,
    val quickTextApp: QuickTextApp = QuickTextApp.MESSAGES,
    val quickContacts: List<QuickContact> = emptyList(),
    /** How you pick the button up to move it. */
    val moveGesture: MoveGesture = MoveGesture.SUPER_LONG_HOLD,
    /** Super long hold: how much longer than a long press to keep holding. */
    val moveHoldMs: Int = 1500,
    /** After the favourites fold out, how much longer to hold still before the button moves. */
    val moveExtraMs: Int = 1500,
    /** In the switcher, keep the finger on an app to open it in split screen or a pop-up. */
    val multiWindowHold: Boolean = true,
    val multiWindowHoldMs: Int = 700,
    val multiWindowMode: MultiWindowMode = MultiWindowMode.SPLIT,
    val switcherFavorites: Set<String> = emptySet(),
    /** Vibrate when the switcher opens, on each app you slide over, and when you pick one. */
    val switcherVibration: Boolean = true,
    val switcherVibrationStrength: Float = 0.7f,
    /** Tick sound for the switcher, separate from the shade's scrolling sound. */
    val switcherSound: TickSound = TickSound.TICK,
    val switcherSoundVolume: Float = 0.5f,
    /** Releasing a long press without picking an app makes the notification icons fall out. */
    val releaseShowsIcons: Boolean = true,
    val activeMode: Int = 0,

    val ai: AiSettings = AiSettings(),

    // Icons
    /** Package of the icon pack in use, "" for the apps' own icons. */
    val iconPack: String = "",
    /** Per-app icon: "pack:<pack package>/<drawable>" or "file:<name>" in the app's icon folder. */
    val customIcons: Map<String, String> = emptyMap(),

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
    /** A click for each notification that scrolls past. */
    val scrollSound: TickSound = TickSound.TICK,
    val scrollSoundVolume: Float = 0.45f,
    val scrollHaptic: Boolean = false,
    val scrollSoundRespectSilent: Boolean = true,
    /** Action wheel: click and vibrate on each slot. */
    val wheelHaptic: Boolean = true,
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
