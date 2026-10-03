package com.thumbshade.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thumbshade.app.ai.AiHub
import com.thumbshade.app.ai.Engagement
import com.thumbshade.app.ai.Nano
import com.thumbshade.app.ai.Urgency
import com.thumbshade.app.data.AiSettings
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.notif.AppInfoCache
import com.thumbshade.app.notif.NotificationRepo
import com.thumbshade.app.notif.ShadeFilter
import com.thumbshade.app.rules.HoldStore
import com.thumbshade.app.rules.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private fun editAi(transform: (AiSettings) -> AiSettings) = SettingsRepo.update { it.copy(ai = transform(it.ai)) }

/** Smart features: summaries, learned priority, smart replies and actions, focus batching. */
@Composable
fun SmartScreen() {
    val context = LocalContext.current
    val s by SettingsRepo.state.collectAsState()
    val ai = s.ai
    val nano by Nano.status.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Nano.check(context) } }

    Section("On your phone only") {
        Hint("Every smart feature runs on this phone. ThumbShade has no internet permission: nothing you receive is sent anywhere.")
    }

    Section("Summaries") {
        SwitchRow("Summarise busy chats and app groups", ai.summaries, "A one-line recap on group chats and folded app groups") { v -> editAi { it.copy(summaries = v) } }
        if (ai.summaries) {
            SliderRow("Summarise chats from", ai.summaryMinMessages.toFloat(), 2f..15f, format = { "${it.roundToInt()} messages" }) { v -> editAi { it.copy(summaryMinMessages = v.roundToInt()) } }
            SwitchRow("Use Gemini Nano when available", ai.useNano, "Google's on-device model (Android AICore) writes the summary. Otherwise ThumbShade's built-in summaries are used.") { v -> editAi { it.copy(useNano = v) } }
            if (ai.useNano) {
                Hint("Gemini Nano: " + nano.label)
                if (nano == Nano.Status.DOWNLOADABLE) {
                    OutlinedButton(onClick = { Nano.download(context) }, modifier = Modifier.fillMaxWidth()) { Text("Let Android download Gemini Nano") }
                }
            }
        }
    }

    Section("Priority") {
        SwitchRow("Learn from what I do", ai.learn, "Opening and replying raise an app or person; swiping away unread lowers them. Old habits fade after a few weeks.") { v -> editAi { it.copy(learn = v) } }
        SwitchRow("Smart order", ai.smartOrder, "Important notifications go nearest your thumb; less important ones further away") { v -> editAi { it.copy(smartOrder = v) } }
        SwitchRow("Minimise low-priority notifications", ai.minimizeLow, "Promotions, likes and newsletters shrink to one line. Long-press to see one in full.") { v -> editAi { it.copy(minimizeLow = v) } }
        SwitchRow("Show urgent / low tags", ai.showBadges) { v -> editAi { it.copy(showBadges = v) } }
        LearnedList()
    }

    Section("Smart replies and actions") {
        SwitchRow("Reply suggestions", ai.smartReplies, "Answer chips under messages, e.g. \"Be there in 5\" when someone asks where you are") { v -> editAi { it.copy(smartReplies = v) } }
        if (ai.smartReplies) LocationRow()
        SwitchRow("Action buttons", ai.smartActions, "Add to calendar, track a parcel, flight status, open an address in Maps, copy a code") { v -> editAi { it.copy(smartActions = v) } }
    }

    Section("Focus batching") {
        SwitchRow(
            "Smart digest", ai.digest,
            "Low-priority notifications (promotions, likes, newsletters) wait quietly and arrive together at your digest times, with one summary. Urgent and personal ones still come straight through.",
        ) { v -> editAi { it.copy(digest = v) } }
        if (ai.digest) {
            DigestTimes(ai.digestTimes)
            SwitchRow("Keep \"normal\" notifications quiet too", ai.quietNormal, "They still show in the shade, but only urgent and personal ones make a sound") { v -> editAi { it.copy(quietNormal = v) } }
            AppSetRow("Never hold these apps", ai.digestNeverApps) { v -> editAi { it.copy(digestNeverApps = v) } }
            val held by HoldStore.held.collectAsState()
            if (held.isNotEmpty()) {
                OutlinedButton(onClick = { HoldStore.releaseAll() }, modifier = Modifier.fillMaxWidth()) { Text("Deliver ${held.size} held now") }
            }
        }
    }

    Section("How your notifications are sorted") {
        val items by NotificationRepo.items.collectAsState()
        val visible = remember(items, s) { ShadeFilter.visible(items, s) }
        if (visible.isEmpty()) Hint("No notifications right now.")
        visible.sortedByDescending { AiHub.priority(it) }.take(12).forEach { item ->
            val u = AiHub.urgency(item)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                AppIcon(item.pkg, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(item.title.ifBlank { item.appName }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    u.label.substringBefore(" ("),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (u) {
                        Urgency.URGENT -> MaterialTheme.colorScheme.error
                        Urgency.PERSONAL -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun LearnedList() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val top = remember(version) { Engagement.top(8) }
    if (top.isEmpty()) {
        Hint("Nothing learned yet. Use your notifications as usual.")
        return
    }
    Text("What ThumbShade has learned", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
    top.forEach { (pkg, score) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
            AppIcon(pkg, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(AppInfoCache.label(context, pkg), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // A bar: right/primary for "you open these", left/outline for "you ignore these".
            Box(Modifier.width(90.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(abs(score).coerceIn(0.05f, 1f))
                        .background(if (score >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                )
            }
        }
    }
    TextButton(onClick = { Engagement.reset(); version++ }) { Text("Forget what was learned") }
}

@Composable
private fun LocationRow() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        ButtonRow("Allow location (optional)", "For the \"Share my location\" reply. Only read when you tap it.") {
            ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}

@Composable
private fun DigestTimes(times: List<Int>) {
    var adding by remember { mutableStateOf(false) }
    Text("Digest times", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        times.sorted().forEach { m ->
            InputChip(
                selected = false,
                onClick = { if (times.size > 1) editAi { it.copy(digestTimes = it.digestTimes - m) } },
                label = { Text(Schedule.formatMinute(m)) },
                trailingIcon = if (times.size > 1) ({ androidx.compose.material3.Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) }) else null,
            )
        }
        TextButton(onClick = { adding = true }) { Text("Add") }
    }
    if (adding) {
        var minute by remember { mutableFloatStateOf(20 * 60f) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a digest time") },
            text = { SliderRow("Time", minute, 0f..1425f, steps = 94, format = { Schedule.formatMinute(it.roundToInt()) }) { minute = it } },
            confirmButton = {
                Button(onClick = {
                    val m = (minute / 15).roundToInt() * 15
                    editAi { it.copy(digestTimes = (it.digestTimes + m).distinct().sorted()) }
                    adding = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
