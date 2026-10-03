package com.thumbshade.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import com.thumbshade.app.overlay.AnimatedIconView
import com.thumbshade.app.data.AnimatedIcon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.thumbshade.app.data.AppSettings
import com.thumbshade.app.data.BrowseStyle
import com.thumbshade.app.data.ClusterSide
import com.thumbshade.app.data.EdgeColorMode
import com.thumbshade.app.data.EdgeStyle
import com.thumbshade.app.data.GestureAction
import com.thumbshade.app.data.GestureMode
import com.thumbshade.app.data.GestureType
import com.thumbshade.app.data.AppBehavior
import com.thumbshade.app.data.CardBg
import com.thumbshade.app.data.CardStyle
import com.thumbshade.app.data.HeaderIcon
import com.thumbshade.app.data.TextAlignChoice
import com.thumbshade.app.data.ChargingAnim
import com.thumbshade.app.data.ChargingMode
import com.thumbshade.app.data.ColorSource
import com.thumbshade.app.data.EmptyBehavior
import com.thumbshade.app.data.KeyboardBehavior
import com.thumbshade.app.data.LandscapeBehavior
import com.thumbshade.app.data.MediaLook
import com.thumbshade.app.data.NotifAnim
import com.thumbshade.app.data.NumberAlign
import com.thumbshade.app.data.ShadeAlign
import com.thumbshade.app.data.SnapStyle
import com.thumbshade.app.overlay.OverlayService
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.data.ShadeAnim
import com.thumbshade.app.notif.AppInfoCache
import com.thumbshade.app.overlay.EdgeLight
import kotlin.math.roundToInt

private fun edit(transform: (AppSettings) -> AppSettings) = SettingsRepo.update(transform)

@Composable
fun NotificationsScreen() {
    val s by SettingsRepo.state.collectAsState()

    Section("Notification behaviour") {
        SwitchRow("Ungroup grouped notifications", s.ungroup, "Hide group summaries and show every notification on its own") { v -> edit { it.copy(ungroup = v) } }
        SwitchRow("Hide items without a time", s.hideNoTime) { v -> edit { it.copy(hideNoTime = v) } }
        SwitchRow("Hide permanent notifications", s.hideOngoing, "Foreground-service and non-dismissable notifications") { v -> edit { it.copy(hideOngoing = v) } }
        SwitchRow("Apply Do Not Disturb", s.applyDnd, "Hide what the system would suppress during Do Not Disturb") { v -> edit { it.copy(applyDnd = v) } }
        SliderRow("Custom snooze length", s.customSnoozeMinutes.toFloat(), 5f..720f, format = { "${it.roundToInt()} min" }) { v -> edit { it.copy(customSnoozeMinutes = v.roundToInt()) } }
    }

    PerAppOverrides(s)

    Section("Notification filter") {
        AppSetRow("Include apps (only these)", s.includeApps) { v -> edit { it.copy(includeApps = v) } }
        AppSetRow("Exclude apps", s.excludeApps) { v -> edit { it.copy(excludeApps = v) } }
        AppSetRow("Top pinned apps", s.pinTop) { v -> edit { it.copy(pinTop = v, pinBottom = it.pinBottom - v) } }
        AppSetRow("Bottom pinned apps", s.pinBottom) { v -> edit { it.copy(pinBottom = v, pinTop = it.pinTop - v) } }
        AppSetRow("Group these apps into one row", s.groupApps) { v -> edit { it.copy(groupApps = v) } }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        SwitchRow("Enable text filter", s.textFilter, "Show or hide notifications by words in their title, text or app name") { v -> edit { it.copy(textFilter = v) } }
        if (s.textFilter) {
            TextRow("Show only (words to match)", s.showOnlyWords, "Only show notifications containing one of these. Separate with |, e.g. out of stock|back in stock") { v ->
                edit { it.copy(showOnlyWords = v) }
            }
            TextRow("Hide matching (words to suppress)", s.hideWords, "Hide notifications containing any of these, e.g. sale|discount|deal") { v ->
                edit { it.copy(hideWords = v) }
            }
        }
    }
}

@Composable
private fun PerAppOverrides(s: AppSettings) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    val apps = (s.perAppColor.keys + s.perAppLines.keys).toSortedSet()

    Section("Per-app overrides") {
        Hint("Force a notification colour and/or how many body lines an app's notifications show.")
        apps.forEach { pkg ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { editing = pkg }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(pkg, Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(AppInfoCache.label(context, pkg))
                    Hint(
                        (if (pkg in s.perAppColor) "Custom colour" else "Default colour") + " · " +
                            (s.perAppLines[pkg]?.let { "$it lines" } ?: "Default lines")
                    )
                }
                IconButton(onClick = { edit { it.copy(perAppColor = it.perAppColor - pkg, perAppLines = it.perAppLines - pkg) } }) {
                    Icon(Icons.Filled.Close, "Remove")
                }
            }
        }
        ButtonRow("Add / choose apps", "Selected apps: ${apps.size}") { picking = true }
    }
    if (picking) {
        AppPickerDialog("Choose an app", emptySet(), single = true, onDismiss = { picking = false }) { chosen ->
            picking = false
            editing = chosen.firstOrNull()
        }
    }
    editing?.let { pkg ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(AppInfoCache.label(context, pkg)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorRow("Notification colour", s.perAppColor[pkg] ?: 0xFF4DD9C9) { c -> edit { it.copy(perAppColor = it.perAppColor + (pkg to c)) } }
                    TextButton(onClick = { edit { it.copy(perAppColor = it.perAppColor - pkg) } }) { Text("Use the app's own colour") }
                    SliderRow("Body lines", (s.perAppLines[pkg] ?: s.bodyMaxLines).toFloat(), 1f..30f) { v ->
                        edit { it.copy(perAppLines = it.perAppLines + (pkg to v.roundToInt())) }
                    }
                    TextButton(onClick = { edit { it.copy(perAppLines = it.perAppLines - pkg) } }) { Text("Use the default lines") }
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text("Done") } },
        )
    }
}

@Composable
fun ButtonScreen() {
    val s by SettingsRepo.state.collectAsState()

    Section("Button position") {
        SwitchRow(
            "Snap to edges",
            s.snapToEdge,
            "Long-press, then drag. Released near the left or right edge, the button docks there; elsewhere it stays where you drop it.",
        ) { v -> edit { it.copy(snapToEdge = v) } }
        ChoiceRow("When docked", SnapStyle.entries, s.snapStyle, { it.label }) { v -> edit { it.copy(snapStyle = v) } }
        SliderRow("Edge zone", s.snapZonePercent.toFloat(), 5f..45f, format = { "${it.roundToInt()}% of the width" }) { v -> edit { it.copy(snapZonePercent = v.roundToInt()) } }
        SliderRow(
            "Fling velocity",
            s.flingVelocityDp.toFloat(),
            0f..4000f,
            step = 100f,
            format = { if (it < 1f) "Off" else "${it.roundToInt()} dp/sec" },
        ) { v -> edit { it.copy(flingVelocityDp = v.roundToInt()) } }
        Hint("Flick the button towards an edge faster than this and it docks there wherever you let go. Lower = easier.")
        SwitchRow("Allow off-screen placement", s.allowOffscreen, "Let the button be dropped partly past the screen edges") { v -> edit { it.copy(allowOffscreen = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { edit { it.copy(buttonDocked = true, dockRight = false) } }, modifier = Modifier.weight(1f)) { Text("Dock left") }
            OutlinedButton(onClick = { edit { it.copy(buttonDocked = true, dockRight = true) } }, modifier = Modifier.weight(1f)) { Text("Dock right") }
        }
        OutlinedButton(onClick = { edit { it.copy(buttonXFrac = -1f, buttonYFrac = -1f, buttonDocked = true, dockRight = true) } }, modifier = Modifier.fillMaxWidth()) {
            Text("Reset position")
        }
    }

    Section("Different appearance when docked") {
        SwitchRow("Use a different look while docked", s.dockedLook) { v -> edit { it.copy(dockedLook = v) } }
        if (s.dockedLook) {
            SliderRow("Docked width", s.dockedWidthDp.toFloat(), 16f..160f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(dockedWidthDp = v.roundToInt()) } }
            SliderRow("Docked height", s.dockedHeightDp.toFloat(), 16f..200f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(dockedHeightDp = v.roundToInt()) } }
            SliderRow("Docked corner radius", s.dockedCornerPercent.toFloat(), 0f..50f, format = { "${it.roundToInt()}%" }) { v -> edit { it.copy(dockedCornerPercent = v.roundToInt()) } }
            ColorRow("Docked colour", s.dockedColor, allowAlpha = true) { c -> edit { it.copy(dockedColor = c) } }
            SliderRow("Docked opacity", s.dockedAlpha, 0.2f..1f, format = { "${(it * 100).roundToInt()}%" }) { v -> edit { it.copy(dockedAlpha = v) } }
        }
    }

    Section("Button behaviour") {
        ChoiceRow("While the keyboard is open", KeyboardBehavior.entries, s.keyboardBehavior, { it.label }) { v -> edit { it.copy(keyboardBehavior = v) } }
        AppSetRow("Selected apps", s.hideInApps) { v -> edit { it.copy(hideInApps = v) } }
        ChoiceRow("In the selected apps", AppBehavior.entries, s.appBehavior, { it.label }) { v -> edit { it.copy(appBehavior = v) } }
        ChoiceRow("When there are no notifications", EmptyBehavior.entries, s.emptyBehavior, { it.label }) { v -> edit { it.copy(emptyBehavior = v) } }
        ChoiceRow("In landscape", LandscapeBehavior.entries, s.landscapeBehavior, { it.label }) { v -> edit { it.copy(landscapeBehavior = v) } }
        SliderRow("\"Hide for a while\" length", s.hideSeconds.toFloat(), 3f..120f, format = { "${it.roundToInt()} s" }) { v -> edit { it.copy(hideSeconds = v.roundToInt()) } }
        Hint("Keyboard and per-app behaviour need the accessibility service.")
    }

    Section("Animations") {
        ChoiceRow("Appear and hide animation", ShadeAnim.entries, s.appearAnim, { it.label }) { v -> edit { it.copy(appearAnim = v) } }
        OutlinedButton(onClick = { OverlayService.instance?.replayAppear() }) { Text("Try it") }
        ChoiceRow("New notification animation", NotifAnim.entries, s.newNotifAnim, { it.label }) { v -> edit { it.copy(newNotifAnim = v) } }
        SliderRow("Intensity", s.newNotifIntensity.toFloat(), 10f..100f, format = { "${it.roundToInt()}%" }) { v -> edit { it.copy(newNotifIntensity = v.roundToInt()) } }
        OutlinedButton(onClick = { OverlayService.instance?.testPulse() }) { Text("Try it") }
    }

    Section("Animated icon") {
        Hint("Animations play on the button itself. Battery and clock animations show the real battery level and time.")
        AnimatedIconRow(s)
        if (s.animatedIcon != AnimatedIcon.NONE) {
            AutoColorRow("Animation colour", s.animatedIconColor) { v -> edit { it.copy(animatedIconColor = v) } }
        }
    }

    Section("Button appearance") {
        SliderRow("Width", s.buttonWidthDp.toFloat(), 24f..160f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(buttonWidthDp = v.roundToInt()) } }
        SliderRow("Height", s.buttonHeightDp.toFloat(), 24f..160f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(buttonHeightDp = v.roundToInt()) } }
        SwitchRow("Per-corner radius", s.perCorner, "Set each corner on its own instead of one value for all four") { v -> edit { it.copy(perCorner = v) } }
        if (s.perCorner) {
            SliderRow("Top-left", s.cornerTopLeftDp.toFloat(), 0f..80f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(cornerTopLeftDp = v.roundToInt()) } }
            SliderRow("Top-right", s.cornerTopRightDp.toFloat(), 0f..80f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(cornerTopRightDp = v.roundToInt()) } }
            SliderRow("Bottom-left", s.cornerBottomLeftDp.toFloat(), 0f..80f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(cornerBottomLeftDp = v.roundToInt()) } }
            SliderRow("Bottom-right", s.cornerBottomRightDp.toFloat(), 0f..80f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(cornerBottomRightDp = v.roundToInt()) } }
        } else {
            SliderRow("Corner radius", s.buttonCornerPercent.toFloat(), 0f..50f, format = { "${it.roundToInt()}%" }) { v -> edit { it.copy(buttonCornerPercent = v.roundToInt()) } }
        }
        ChoiceRow("Button background", ColorSource.entries, s.buttonBgSource, { it.label }) { v -> edit { it.copy(buttonBgSource = v) } }
        if (s.buttonBgSource == ColorSource.CUSTOM || s.buttonBgSource == ColorSource.NOTIFICATION) {
            ColorRow(if (s.buttonBgSource == ColorSource.CUSTOM) "Button colour" else "Colour when there's none", s.buttonColor, allowAlpha = true) { c -> edit { it.copy(buttonColor = c) } }
        }
        ChoiceRow("Button border", ColorSource.entries, s.buttonBorderSource, { it.label }) { v -> edit { it.copy(buttonBorderSource = v) } }
        if (s.buttonBorderSource != ColorSource.NONE) {
            SliderRow("Border thickness", s.buttonBorderDp.toFloat(), 0f..8f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(buttonBorderDp = v.roundToInt()) } }
            if (s.buttonBorderSource == ColorSource.CUSTOM) ColorRow("Border colour", s.buttonBorderColor) { c -> edit { it.copy(buttonBorderColor = c) } }
        }
        SliderRow("Whole-button opacity", s.buttonAlpha, 0.2f..1f, format = { "${(it * 100).roundToInt()}%" }) { v -> edit { it.copy(buttonAlpha = v) } }
        SwitchRow("Show icon of the latest notification", s.showLatestIcon) { v -> edit { it.copy(showLatestIcon = v) } }
    }

    Section("Number on button") {
        SwitchRow("Show number of notifications", s.showCount) { v -> edit { it.copy(showCount = v) } }
        if (s.showCount) {
            SliderRow("Number size", s.numberSizeSp.toFloat(), 8f..40f, format = { "${it.roundToInt()} sp" }) { v -> edit { it.copy(numberSizeSp = v.roundToInt()) } }
            SwitchRow("Bold number", s.numberBold) { v -> edit { it.copy(numberBold = v) } }
            SwitchRow("Hide the number when there's only one", s.numberHideSingle) { v -> edit { it.copy(numberHideSingle = v) } }
            ChoiceRow("Position", NumberAlign.entries, s.numberAlign, { it.label }) { v -> edit { it.copy(numberAlign = v) } }
            ColorRow("Number colour", s.numberColor) { c -> edit { it.copy(numberColor = c) } }
        }
    }

    Section("While media plays") {
        ChoiceRow("Show on the button", MediaLook.entries, s.mediaLook, { it.label }) { v -> edit { it.copy(mediaLook = v) } }
        if (s.mediaLook != MediaLook.NOTHING) {
            SwitchRow("Only while playing", s.mediaOnlyPlaying, "Off: also while paused") { v -> edit { it.copy(mediaOnlyPlaying = v) } }
            if (s.mediaLook == MediaLook.ALBUM_ART) {
                SliderRow("Dim the cover", s.mediaDimPercent.toFloat(), 0f..80f, format = { "${it.roundToInt()}%" }) { v -> edit { it.copy(mediaDimPercent = v.roundToInt()) } }
            } else if (s.mediaLook != MediaLook.RECORD && s.mediaLook != MediaLook.CD) {
                ColorRow("Animation colour", s.mediaAnimColor) { c -> edit { it.copy(mediaAnimColor = c) } }
            }
        }
    }

    Section("Charging indicator") {
        ChoiceRow("Indicator", ChargingMode.entries, s.chargingMode, { it.label }) { v -> edit { it.copy(chargingMode = v) } }
        if (s.chargingMode != ChargingMode.NONE) {
            SliderRow("Ring thickness", s.chargingThicknessDp.toFloat(), 1f..10f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(chargingThicknessDp = v.roundToInt()) } }
            ChoiceRow("Animation", ChargingAnim.entries, s.chargingAnim, { it.label }) { v -> edit { it.copy(chargingAnim = v) } }
        }
    }

    Section("Notification icon cluster") {
        SwitchRow("Show app icons around the button", s.iconCluster) { v -> edit { it.copy(iconCluster = v) } }
        if (s.iconCluster) {
            ChoiceRow("Layout", ClusterSide.entries, s.clusterSide, { it.label }) { v -> edit { it.copy(clusterSide = v) } }
            SliderRow("Max icons", s.clusterMax.toFloat(), 1f..12f, steps = 10) { v -> edit { it.copy(clusterMax = v.roundToInt()) } }
            SliderRow("Icon size", s.clusterIconDp.toFloat(), 16f..48f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(clusterIconDp = v.roundToInt()) } }
            SwitchRow("Monochrome icons", s.monochromeIcons) { v -> edit { it.copy(monochromeIcons = v) } }
        }
    }

    GestureModes(s)
}

@Composable
private fun GestureModes(s: AppSettings) {
    Section("Gesture modes") {
        Hint("Tap and swipe the button. Group gestures into modes and switch modes with a gesture set to \"Switch to the next mode\".")
        s.modes.forEachIndexed { index, mode ->
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    mode.name + if (index == s.activeMode) "  (active)" else "",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (index != s.activeMode) TextButton(onClick = { edit { it.copy(activeMode = index) } }) { Text("Use") }
                if (s.modes.size > 1) {
                    IconButton(onClick = {
                        edit { st ->
                            val list = st.modes.toMutableList().apply { removeAt(index) }
                            st.copy(modes = list, activeMode = st.activeMode.coerceAtMost(list.lastIndex))
                        }
                    }) { Icon(Icons.Filled.Delete, "Delete mode") }
                }
            }
            TextRow("Mode name", mode.name) { v -> updateMode(index) { it.copy(name = v) } }
            GestureRow("Tap", mode.tap) { a -> updateMode(index) { it.copy(tap = a) } }
            GestureRow("Swipe up", mode.up) { a -> updateMode(index) { it.copy(up = a) } }
            GestureRow("Swipe down", mode.down) { a -> updateMode(index) { it.copy(down = a) } }
            GestureRow("Swipe left", mode.left) { a -> updateMode(index) { it.copy(left = a) } }
            GestureRow("Swipe right", mode.right) { a -> updateMode(index) { it.copy(right = a) } }
        }
        Button(onClick = { edit { it.copy(modes = it.modes + GestureMode(name = "Mode ${it.modes.size + 1}")) } }, modifier = Modifier.fillMaxWidth()) {
            Text("Add mode")
        }
    }
}

private fun updateMode(index: Int, transform: (GestureMode) -> GestureMode) = edit { s ->
    s.copy(modes = s.modes.mapIndexed { i, m -> if (i == index) transform(m) else m })
}

@Composable
private fun GestureRow(title: String, action: GestureAction, onChange: (GestureAction) -> Unit) {
    val context = LocalContext.current
    val s by SettingsRepo.state.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var pickApp by remember { mutableStateOf(false) }
    var pickText by remember { mutableStateOf(false) }
    var pickScreenApp by remember { mutableStateOf(false) }
    var screenApp by remember { mutableStateOf<String?>(null) }
    var pickShortcut by remember { mutableStateOf(false) }
    var editIntent by remember { mutableStateOf(false) }
    val label = when (action.type) {
        GestureType.OPEN_APP -> "Open " + AppInfoCache.label(context, action.arg)
        GestureType.APP_SCREEN, GestureType.SHORTCUT, GestureType.CUSTOM_INTENT -> action.label.ifBlank { action.type.label }
        GestureType.PASTE_TEXT -> "Paste \"" + action.arg.take(20) + "\""
        else -> action.type.label
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { choosing = true }
            .padding(vertical = 8.dp),
    ) {
        Text(title, Modifier.weight(1f))
        Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(GestureType.entries) { t ->
                        Text(
                            t.label,
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    choosing = false
                                    when (t) {
                                        GestureType.OPEN_APP -> pickApp = true
                                        GestureType.PASTE_TEXT -> pickText = true
                                        GestureType.APP_SCREEN -> pickScreenApp = true
                                        GestureType.SHORTCUT -> pickShortcut = true
                                        GestureType.CUSTOM_INTENT -> editIntent = true
                                        else -> onChange(GestureAction(t))
                                    }
                                }
                                .padding(vertical = 10.dp),
                            color = if (t == action.type) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } },
        )
    }
    if (pickApp) {
        AppPickerDialog("Open which app?", emptySet(), single = true, onDismiss = { pickApp = false }) { chosen ->
            pickApp = false
            chosen.firstOrNull()?.let { onChange(GestureAction(GestureType.OPEN_APP, it)) }
        }
    }
    if (pickScreenApp) {
        AppPickerDialog("Which app's screen?", emptySet(), single = true, onDismiss = { pickScreenApp = false }) { chosen ->
            pickScreenApp = false
            screenApp = chosen.firstOrNull()
        }
    }
    screenApp?.let { pkg ->
        AppScreenPicker(pkg, onDismiss = { screenApp = null }) { a ->
            screenApp = null
            onChange(a)
        }
    }
    if (pickShortcut) {
        ShortcutPicker(onDismiss = { pickShortcut = false }) { a ->
            pickShortcut = false
            onChange(a)
        }
    }
    if (editIntent) {
        CustomIntentDialog(action, onDismiss = { editIntent = false }) { a ->
            editIntent = false
            onChange(a)
        }
    }
    if (pickText) {
        AlertDialog(
            onDismissRequest = { pickText = false },
            title = { Text("Paste which text?") },
            text = {
                Column {
                    if (s.savedTexts.isEmpty()) Hint("Add saved texts on the General tab first.")
                    s.savedTexts.forEach { t ->
                        Text(
                            t,
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pickText = false
                                    onChange(GestureAction(GestureType.PASTE_TEXT, t))
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickText = false }) { Text("Close") } },
        )
    }
}

@Composable
fun ShadeSettingsScreen() {
    val s by SettingsRepo.state.collectAsState()
    val context = LocalContext.current

    Section("Shade behaviour") {
        SliderRow("Max height", s.shadeMaxHeight, 0.3f..1f, format = { "${(it * 100).roundToInt()}%" }) { v -> edit { it.copy(shadeMaxHeight = v) } }
        SliderRow("Width", s.shadeWidth, 0.5f..1f, format = { "${(it * 100).roundToInt()}%" }) { v -> edit { it.copy(shadeWidth = v) } }
        ChoiceRow("Position", ShadeAlign.entries, s.shadeAlign, { it.label }) { v -> edit { it.copy(shadeAlign = v) } }
        SwitchRow("Newest at the bottom", s.newestAtBottom, "Closest to your thumb") { v -> edit { it.copy(newestAtBottom = v) } }
        SliderRow("Space between notifications", s.rowSpacingDp.toFloat(), 0f..32f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(rowSpacingDp = v.roundToInt()) } }
        ChoiceRow("Background overlay", com.thumbshade.app.data.ShadeOverlay.entries, s.shadeOverlay, { it.label }) { v -> edit { it.copy(shadeOverlay = v) } }
        if (s.shadeOverlay == com.thumbshade.app.data.ShadeOverlay.DIM || s.shadeOverlay == com.thumbshade.app.data.ShadeOverlay.DIM_BLUR) {
            SliderRow("Dim amount", s.dimBehind, 0f..0.9f, format = { "${(it * 100).roundToInt()}%" }) { v -> edit { it.copy(dimBehind = v) } }
        }
        if (s.shadeOverlay == com.thumbshade.app.data.ShadeOverlay.BLUR || s.shadeOverlay == com.thumbshade.app.data.ShadeOverlay.DIM_BLUR) {
            SliderRow("Blur strength", s.blurRadiusDp.toFloat(), 4f..80f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(blurRadiusDp = v.roundToInt()) } }
            if (android.os.Build.VERSION.SDK_INT < 31) Hint("Blur needs Android 12 or newer.")
        }
        SwitchRow("Remember scroll position", s.rememberScroll, "When reopening the shade, return to where you were") { v -> edit { it.copy(rememberScroll = v) } }
        ChoiceRow("Browsing mode", BrowseStyle.entries, s.browseStyle, { it.label }) { v -> edit { it.copy(browseStyle = v) } }
        ChoiceRow("Open and close animation", ShadeAnim.entries, s.shadeAnim, { it.label }) { v -> edit { it.copy(shadeAnim = v) } }
        SwitchRow(
            "Push/pull to close", s.pushPullClose,
            "At the bottom of the list pull up past the edge to close; at the top, pull down. The shade follows your finger with a rubber-band feel.",
        ) { v -> edit { it.copy(pushPullClose = v) } }
        SwitchRow(
            "Wrap-around scrolling", s.wrapAround && !s.pushPullClose,
            if (s.pushPullClose) "Only available while Push/pull to close is off" else "Pull past the last notification to jump back to the first, and the other way round",
        ) { v -> if (!s.pushPullClose) edit { it.copy(wrapAround = v) } }
        if (s.pushPullClose || s.wrapAround) {
            SliderRow("Pull distance", s.pullCloseDp.toFloat(), 40f..240f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(pullCloseDp = v.roundToInt()) } }
        }
        SwitchRow("Swipe to dismiss", s.swipeToDismiss) { v -> edit { it.copy(swipeToDismiss = v) } }
        if (s.swipeToDismiss) {
            SliderRow("Swipe distance to dismiss", s.swipeDismissFraction, 0.15f..0.8f, format = { "${(it * 100).roundToInt()}% of the width" }) { v -> edit { it.copy(swipeDismissFraction = v) } }
        }
        SwitchRow("Close after opening a notification", s.closeAfterOpen) { v -> edit { it.copy(closeAfterOpen = v) } }
        SwitchRow("Close when it's empty", s.closeWhenEmpty) { v -> edit { it.copy(closeWhenEmpty = v) } }
    }

    NotificationCardSections(s)

    Section("Screen lighting") {
        SwitchRow("Light up for new notifications", s.edgeLight) { v -> edit { it.copy(edgeLight = v) } }
        ChoiceRow("Screen border effect", EdgeStyle.entries.filterNot { it.aroundButton }, s.edgeStyle, { it.label }) { v -> edit { it.copy(edgeStyle = v) } }
        val fxOptions: List<EdgeStyle?> = listOf<EdgeStyle?>(null) + EdgeStyle.entries.filter { it.aroundButton }
        ChoiceRow("Button effect", fxOptions, s.buttonEffect, { it?.label ?: "None" }) { v -> edit { it.copy(buttonEffect = v) } }
        Hint("The border and button effects play together; the button effect follows the button's shape.")
        ChoiceRow("Colour", EdgeColorMode.entries, s.edgeColorMode, { it.label }) { v -> edit { it.copy(edgeColorMode = v) } }
        if (s.edgeColorMode == EdgeColorMode.CUSTOM) ColorRow("Custom colour", s.edgeCustomColor) { c -> edit { it.copy(edgeCustomColor = c) } }
        SliderRow("Duration", s.edgeDurationMs / 1000f, 1f..10f, format = { "%.1f s".format(it) }) { v -> edit { it.copy(edgeDurationMs = (v * 1000).roundToInt()) } }
        SliderRow("Thickness", s.edgeThicknessDp.toFloat(), 2f..16f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(edgeThicknessDp = v.roundToInt()) } }
        SwitchRow("Wake the screen", s.edgeWakeScreen) { v -> edit { it.copy(edgeWakeScreen = v) } }
        SwitchRow("Only while the screen is on", s.edgeOnlyScreenOn) { v -> edit { it.copy(edgeOnlyScreenOn = v) } }
        Button(onClick = { EdgeLight.preview(context) }, modifier = Modifier.fillMaxWidth()) { Text("Try it") }
    }
}

private fun editCard(transform: (CardStyle) -> CardStyle) = edit { it.copy(card = transform(it.card)) }

private fun sp(v: Float) = "${v.roundToInt()} sp"
private fun dpLabel(v: Float) = "${v.roundToInt()} dp"

/** Text settings shared by app name, subtitle, title, body and time. */
@Composable
private fun TextStyleRows(label: String, sizeSp: Int, bold: Boolean, color: Long, range: ClosedFloatingPointRange<Float>, onSize: (Int) -> Unit, onBold: (Boolean) -> Unit, onColor: (Long) -> Unit) {
    SliderRow("$label text size", sizeSp.toFloat(), range, format = ::sp) { onSize(it.roundToInt()) }
    SwitchRow("Bold $label".lowercase().replaceFirstChar { it.uppercase() }, bold) { onBold(it) }
    AutoColorRow("$label colour", color, onColor)
}

@Composable
private fun NotificationCardSections(s: AppSettings) {
    val c = s.card

    Section("Card background and border") {
        ChoiceRow("Background", CardBg.entries, c.bgSource, { it.label }) { v -> editCard { it.copy(bgSource = v) } }
        if (c.bgSource == CardBg.CUSTOM || c.bgSource == CardBg.GRADIENT) {
            ColorRow(if (c.bgSource == CardBg.GRADIENT) "Gradient start" else "Background colour", c.bgColor, allowAlpha = true) { v -> editCard { it.copy(bgColor = v) } }
        }
        if (c.bgSource == CardBg.GRADIENT) {
            ColorRow("Gradient end", c.gradientEnd, allowAlpha = true) { v -> editCard { it.copy(gradientEnd = v) } }
        }
        SliderRow("Corner radius", s.cardCornerDp.toFloat(), 0f..40f, format = ::dpLabel) { v -> edit { it.copy(cardCornerDp = v.roundToInt()) } }
        SliderRow("Inner padding", c.paddingDp.toFloat(), 4f..28f, format = ::dpLabel) { v -> editCard { it.copy(paddingDp = v.roundToInt()) } }
        SliderRow("Border width", c.borderWidthDp.toFloat(), 0f..6f, format = ::dpLabel) { v -> editCard { it.copy(borderWidthDp = v.roundToInt()) } }
        if (c.borderWidthDp > 0) {
            SwitchRow("Border in the notification's colour", c.borderFromNotification) { v -> editCard { it.copy(borderFromNotification = v) } }
            if (!c.borderFromNotification) ColorRow("Border colour", c.borderColor, allowAlpha = true) { v -> editCard { it.copy(borderColor = v) } }
        }
        AutoColorRow("Fallback colour when the app sets none", c.fallbackColor) { v -> editCard { it.copy(fallbackColor = v) } }
    }

    Section("Apps' own layouts") {
        SwitchRow("Show apps' custom layouts", c.appLayouts, "Weather, sports, timers and other notifications that draw their own design are shown as the app made them") { v -> editCard { it.copy(appLayouts = v) } }
    }

    Section("Header") {
        ChoiceRow("Header icon", HeaderIcon.entries, c.headerIcon, { it.label }) { v -> editCard { it.copy(headerIcon = v) } }
        if (c.headerIcon != HeaderIcon.NONE) {
            SliderRow("Header icon size", c.headerIconDp.toFloat(), 12f..40f, format = ::dpLabel) { v -> editCard { it.copy(headerIconDp = v.roundToInt()) } }
        }
        if (c.headerIcon == HeaderIcon.SENDER) {
            SwitchRow("Show app icon badge", c.appBadge, "A small app icon on the sender's picture") { v -> editCard { it.copy(appBadge = v) } }
        }
        SwitchRow("Snooze and menu buttons in the header", c.headerButtons) { v -> editCard { it.copy(headerButtons = v) } }
        SwitchRow("Show app name", c.showAppName) { v -> editCard { it.copy(showAppName = v) } }
        if (c.showAppName) {
            TextStyleRows("App name", c.appNameSp, c.appNameBold, c.appNameColor, 8f..22f,
                { v -> editCard { it.copy(appNameSp = v) } }, { v -> editCard { it.copy(appNameBold = v) } }, { v -> editCard { it.copy(appNameColor = v) } })
        }
        SwitchRow("Show subtitle", c.showSubtitle, "The conversation or account name some apps add") { v -> editCard { it.copy(showSubtitle = v) } }
        if (c.showSubtitle) {
            SwitchRow("Subtitle in the header", c.subtitleInHeader, "Off: show it under the title") { v -> editCard { it.copy(subtitleInHeader = v) } }
            ChoiceRow("Header lines", listOf(1, 2, 3), c.headerLines, { if (it == 1) "1 line" else "$it lines" }) { v -> editCard { it.copy(headerLines = v) } }
            TextStyleRows("Subtitle", c.subtitleSp, c.subtitleBold, c.subtitleColor, 8f..22f,
                { v -> editCard { it.copy(subtitleSp = v) } }, { v -> editCard { it.copy(subtitleBold = v) } }, { v -> editCard { it.copy(subtitleColor = v) } })
        }
    }

    Section("Title") {
        SwitchRow("Show title", c.showTitle) { v -> editCard { it.copy(showTitle = v) } }
        if (c.showTitle) {
            TextStyleRows("Title", c.titleSp, c.titleBold, c.titleColor, 10f..28f,
                { v -> editCard { it.copy(titleSp = v) } }, { v -> editCard { it.copy(titleBold = v) } }, { v -> editCard { it.copy(titleColor = v) } })
            SliderRow("Title lines", c.titleLines.toFloat(), 1f..6f, format = { "${it.roundToInt()}" }) { v -> editCard { it.copy(titleLines = v.roundToInt()) } }
            ChoiceRow("Title alignment", TextAlignChoice.entries, c.titleAlign, { it.label }) { v -> editCard { it.copy(titleAlign = v) } }
        }
    }

    Section("Body") {
        SwitchRow("Show body", c.showBody) { v -> editCard { it.copy(showBody = v) } }
        if (c.showBody) {
            TextStyleRows("Body", c.bodySp, c.bodyBold, c.bodyColor, 10f..26f,
                { v -> editCard { it.copy(bodySp = v) } }, { v -> editCard { it.copy(bodyBold = v) } }, { v -> editCard { it.copy(bodyColor = v) } })
            SwitchRow("Limit body lines", c.limitBodyLines) { v -> editCard { it.copy(limitBodyLines = v) } }
            if (c.limitBodyLines) SliderRow("Line limit", s.bodyMaxLines.toFloat(), 1f..20f) { v -> edit { it.copy(bodyMaxLines = v.roundToInt()) } }
            ChoiceRow("Body alignment", TextAlignChoice.entries, c.bodyAlign, { it.label }) { v -> editCard { it.copy(bodyAlign = v) } }
            SwitchRow("Hide title from body", c.hideTitleFromBody, "Don't repeat the sender's name on every chat line") { v -> editCard { it.copy(hideTitleFromBody = v) } }
        }
        SwitchRow("Show pictures", s.showPictures) { v -> edit { it.copy(showPictures = v) } }
    }

    Section("Time") {
        SwitchRow("Show time", c.showTime) { v -> editCard { it.copy(showTime = v) } }
        if (c.showTime) {
            SwitchRow("Clock time instead of \"5 min ago\"", c.clockTime) { v -> editCard { it.copy(clockTime = v) } }
            TextStyleRows("Time", c.timeSp, c.timeBold, c.timeColor, 8f..20f,
                { v -> editCard { it.copy(timeSp = v) } }, { v -> editCard { it.copy(timeBold = v) } }, { v -> editCard { it.copy(timeColor = v) } })
        }
    }

    Section("Large icon") {
        SwitchRow("Show large icon", s.showLargeIcon) { v -> edit { it.copy(showLargeIcon = v) } }
        if (s.showLargeIcon) {
            SliderRow("Icon size", c.largeIconDp.toFloat(), 24f..96f, format = ::dpLabel) { v -> editCard { it.copy(largeIconDp = v.roundToInt()) } }
            SwitchRow("Round icon", c.roundLargeIcon) { v -> editCard { it.copy(roundLargeIcon = v) } }
            SwitchRow("Show the sender's picture for chats", c.senderPicture) { v -> editCard { it.copy(senderPicture = v) } }
            SwitchRow("Hide it if it's already in the header", c.hideSenderIfInHeader) { v -> editCard { it.copy(hideSenderIfInHeader = v) } }
        }
    }

    Section("Buttons and progress bar") {
        SwitchRow("Show buttons (actions, snooze)", s.showActions) { v -> edit { it.copy(showActions = v) } }
        if (s.showActions) {
            TextStyleRows("Button", c.buttonSp, c.buttonBold, c.buttonColor, 10f..22f,
                { v -> editCard { it.copy(buttonSp = v) } }, { v -> editCard { it.copy(buttonBold = v) } }, { v -> editCard { it.copy(buttonColor = v) } })
            SwitchRow("Button background", c.buttonBackground) { v -> editCard { it.copy(buttonBackground = v) } }
            if (c.buttonBackground) ColorRow("Button background colour", c.buttonBgColor, allowAlpha = true) { v -> editCard { it.copy(buttonBgColor = v) } }
            SwitchRow("Button border", c.buttonBorder) { v -> editCard { it.copy(buttonBorder = v) } }
            if (c.buttonBorder) {
                SliderRow("Border size", c.buttonBorderDp.toFloat(), 1f..4f, format = ::dpLabel) { v -> editCard { it.copy(buttonBorderDp = v.roundToInt()) } }
                ColorRow("Button border colour", c.buttonBorderColor, allowAlpha = true) { v -> editCard { it.copy(buttonBorderColor = v) } }
            }
            SliderRow("Button corner radius", c.buttonCornerDp.toFloat(), 0f..24f, format = ::dpLabel) { v -> editCard { it.copy(buttonCornerDp = v.roundToInt()) } }
            SliderRow("Button padding", c.buttonPaddingDp.toFloat(), 2f..16f, format = ::dpLabel) { v -> editCard { it.copy(buttonPaddingDp = v.roundToInt()) } }
        }
        AutoColorRow("Progress bar colour", c.progressColor) { v -> editCard { it.copy(progressColor = v) } }
    }
}

@Composable
private fun AnimatedIconRow(s: AppSettings) {
    var open by remember { mutableStateOf(false) }
    val accent = Color(currentAccent(LocalContext.current, s))
    val color = if (s.animatedIconColor != 0L) Color(s.animatedIconColor) else accent
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { open = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Animation", Modifier.weight(1f))
        Text(s.animatedIcon.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Animated icon") },
            text = {
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.heightIn(max = 460.dp)) {
                    items(AnimatedIcon.entries) { icon ->
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    edit { it.copy(animatedIcon = icon) }
                                    open = false
                                }
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF15181C))
                                    .then(if (icon == s.animatedIcon) Modifier.border(2.dp, color, CircleShape) else Modifier),
                            ) {
                                AnimatedIconView(icon, color, Modifier.fillMaxSize())
                            }
                            Text(icon.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}
