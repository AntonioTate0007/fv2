package com.thumbshade.app.ui

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
import com.thumbshade.app.data.KeyboardBehavior
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

    Section("Button behaviour") {
        SwitchRow("Snap to edge", s.snapToEdge, "Long-press and drag to move; it snaps to the nearest side") { v -> edit { it.copy(snapToEdge = v) } }
        SwitchRow("Hide when there are no notifications", s.hideWhenEmpty) { v -> edit { it.copy(hideWhenEmpty = v) } }
        ChoiceRow("While typing", KeyboardBehavior.entries, s.keyboardBehavior, { it.label }) { v -> edit { it.copy(keyboardBehavior = v) } }
        Hint("Keyboard and per-app behaviour need the accessibility service.")
        AppSetRow("Hide in these apps", s.hideInApps) { v -> edit { it.copy(hideInApps = v) } }
        SliderRow("\"Hide for a while\" length", s.hideSeconds.toFloat(), 3f..120f, format = { "${it.roundToInt()} s" }) { v -> edit { it.copy(hideSeconds = v.roundToInt()) } }
        OutlinedButton(onClick = { edit { it.copy(buttonX = -1, buttonY = -1) } }, modifier = Modifier.fillMaxWidth()) { Text("Reset position") }
    }

    Section("Button appearance") {
        SliderRow("Width", s.buttonWidthDp.toFloat(), 24f..160f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(buttonWidthDp = v.roundToInt()) } }
        SliderRow("Height", s.buttonHeightDp.toFloat(), 24f..160f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(buttonHeightDp = v.roundToInt()) } }
        SliderRow("Corner radius", s.buttonCornerPercent.toFloat(), 0f..50f, format = { "${it.roundToInt()}%" }) { v -> edit { it.copy(buttonCornerPercent = v.roundToInt()) } }
        ColorRow("Button colour", s.buttonColor, allowAlpha = true) { c -> edit { it.copy(buttonColor = c) } }
        SliderRow("Border thickness", s.buttonBorderDp.toFloat(), 0f..8f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(buttonBorderDp = v.roundToInt()) } }
        ColorRow("Border colour", s.buttonBorderColor) { c -> edit { it.copy(buttonBorderColor = c) } }
        SliderRow("Whole-button opacity", s.buttonAlpha, 0.2f..1f, format = { "${(it * 100).roundToInt()}%" }) { v -> edit { it.copy(buttonAlpha = v) } }
        SwitchRow("Show number of notifications", s.showCount) { v -> edit { it.copy(showCount = v) } }
        SwitchRow("Show icon of the latest notification", s.showLatestIcon) { v -> edit { it.copy(showLatestIcon = v) } }
        SwitchRow("Album art while music plays", s.showAlbumArt) { v -> edit { it.copy(showAlbumArt = v) } }
        SwitchRow("Charging ring", s.chargingRing, "Shows the battery level around the button while charging") { v -> edit { it.copy(chargingRing = v) } }
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
    val label = when (action.type) {
        GestureType.OPEN_APP -> "Open " + AppInfoCache.label(context, action.arg)
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
        SwitchRow("Newest at the bottom", s.newestAtBottom, "Closest to your thumb") { v -> edit { it.copy(newestAtBottom = v) } }
        SliderRow("Background dim", s.dimBehind, 0f..0.9f, format = { "${(it * 100).roundToInt()}%" }) { v -> edit { it.copy(dimBehind = v) } }
        ChoiceRow("Browsing style", BrowseStyle.entries, s.browseStyle, { it.label }) { v -> edit { it.copy(browseStyle = v) } }
        ChoiceRow("Open and close animation", ShadeAnim.entries, s.shadeAnim, { it.label }) { v -> edit { it.copy(shadeAnim = v) } }
        SwitchRow("Swipe to dismiss", s.swipeToDismiss) { v -> edit { it.copy(swipeToDismiss = v) } }
        SwitchRow("Close after opening a notification", s.closeAfterOpen) { v -> edit { it.copy(closeAfterOpen = v) } }
        SwitchRow("Close when it's empty", s.closeWhenEmpty) { v -> edit { it.copy(closeWhenEmpty = v) } }
    }

    Section("Notification style") {
        SliderRow("Corner radius", s.cardCornerDp.toFloat(), 0f..36f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(cardCornerDp = v.roundToInt()) } }
        SwitchRow("Theme card colour", s.cardColor == 0L) { v -> edit { it.copy(cardColor = if (v) 0L else 0xF0202124) } }
        if (s.cardColor != 0L) ColorRow("Card colour", s.cardColor, allowAlpha = true) { c -> edit { it.copy(cardColor = c) } }
        SliderRow("Body lines", s.bodyMaxLines.toFloat(), 1f..20f) { v -> edit { it.copy(bodyMaxLines = v.roundToInt()) } }
        SwitchRow("Show large icon", s.showLargeIcon) { v -> edit { it.copy(showLargeIcon = v) } }
        SwitchRow("Show pictures", s.showPictures) { v -> edit { it.copy(showPictures = v) } }
        SwitchRow("Show buttons (actions, snooze)", s.showActions) { v -> edit { it.copy(showActions = v) } }
    }

    Section("Media player") {
        SwitchRow("Built-in media player", s.showMedia, "Play/pause, skip, seek and the player's own buttons") { v -> edit { it.copy(showMedia = v) } }
        SwitchRow("Album art as background", s.albumArtBackground) { v -> edit { it.copy(albumArtBackground = v) } }
    }

    Section("Screen lighting") {
        SwitchRow("Light up for new notifications", s.edgeLight) { v -> edit { it.copy(edgeLight = v) } }
        ChoiceRow("Screen border effect", EdgeStyle.entries.filterNot { it.aroundButton }, s.edgeStyle, { it.label }) { v -> edit { it.copy(edgeStyle = v) } }
        val fxOptions: List<EdgeStyle?> = listOf<EdgeStyle?>(null) + EdgeStyle.entries.filter { it.aroundButton }
        ChoiceRow("Button effect", fxOptions, s.buttonEffect, { it?.label ?: "None" }) { v -> edit { it.copy(buttonEffect = v) } }
        ChoiceRow("Colour", EdgeColorMode.entries, s.edgeColorMode, { it.label }) { v -> edit { it.copy(edgeColorMode = v) } }
        if (s.edgeColorMode == EdgeColorMode.CUSTOM) ColorRow("Custom colour", s.edgeCustomColor) { c -> edit { it.copy(edgeCustomColor = c) } }
        SliderRow("Duration", s.edgeDurationMs / 1000f, 1f..10f, format = { "%.1f s".format(it) }) { v -> edit { it.copy(edgeDurationMs = (v * 1000).roundToInt()) } }
        SliderRow("Thickness", s.edgeThicknessDp.toFloat(), 2f..16f, format = { "${it.roundToInt()} dp" }) { v -> edit { it.copy(edgeThicknessDp = v.roundToInt()) } }
        SwitchRow("Wake the screen", s.edgeWakeScreen) { v -> edit { it.copy(edgeWakeScreen = v) } }
        SwitchRow("Only while the screen is on", s.edgeOnlyScreenOn) { v -> edit { it.copy(edgeOnlyScreenOn = v) } }
        Button(onClick = { EdgeLight.preview(context) }, modifier = Modifier.fillMaxWidth()) { Text("Try it") }
    }
}
