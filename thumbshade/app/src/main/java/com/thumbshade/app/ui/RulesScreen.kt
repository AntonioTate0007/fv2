package com.thumbshade.app.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thumbshade.app.data.EdgeStyle
import com.thumbshade.app.notif.AppInfoCache
import com.thumbshade.app.rules.ActionType
import com.thumbshade.app.rules.BatchMode
import com.thumbshade.app.rules.Cat
import com.thumbshade.app.rules.Cond
import com.thumbshade.app.rules.CondGroup
import com.thumbshade.app.rules.CondType
import com.thumbshade.app.rules.DeviceAspect
import com.thumbshade.app.rules.Effects
import com.thumbshade.app.rules.Flag
import com.thumbshade.app.rules.HoldStore
import com.thumbshade.app.rules.RingerChoice
import com.thumbshade.app.rules.Rule
import com.thumbshade.app.rules.RuleAction
import com.thumbshade.app.rules.RuleLog
import com.thumbshade.app.rules.RuleMatcher
import com.thumbshade.app.rules.RuleStore
import com.thumbshade.app.rules.Schedule
import com.thumbshade.app.rules.Templates
import com.thumbshade.app.rules.TextField
import com.thumbshade.app.rules.Vibe
import com.thumbshade.app.rules.WordMode
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun RulesScreen(modifier: Modifier, pendingRulePkg: String?, onRulePkgConsumed: () -> Unit) {
    val context = LocalContext.current
    val rules by RuleStore.rules.collectAsState()
    val held by HoldStore.held.collectAsState()
    val log by RuleLog.entries.collectAsState()
    var editing by remember { mutableStateOf<Rule?>(null) }
    var templates by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(pendingRulePkg) {
        if (pendingRulePkg != null) {
            editing = Rule(name = "Rule for " + AppInfoCache.label(context, pendingRulePkg), apps = setOf(pendingRulePkg))
            onRulePkgConsumed()
        }
    }

    val current = editing
    if (current != null) {
        RuleEditor(modifier, current, onCancel = { editing = null }) { saved ->
            RuleStore.upsert(saved)
            editing = null
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Hint("A rule watches for the notifications you describe and acts on them. Rules run from top to bottom.")
        }
        items(rules, key = { it.id }) { rule ->
            RuleCard(rule, first = rule == rules.first(), last = rule == rules.last(), onEdit = { editing = rule })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { editing = Rule() }, modifier = Modifier.weight(1f)) { Text("Add rule") }
                OutlinedButton(onClick = { templates = true }, modifier = Modifier.weight(1f)) { Text("From a template") }
            }
        }
        item {
            Section("Held notifications") {
                if (held.isEmpty()) Hint("Nothing is being held right now.")
                held.sortedBy { it.releaseAt }.forEach { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(h.pkg, Modifier.padding(end = 10.dp).size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.title.ifBlank { h.appName }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Hint("${h.appName} · until ${clockTime(h.releaseAt)}")
                        }
                        TextButton(onClick = { HoldStore.release(h.key) }) { Text("Release") }
                    }
                }
                if (held.isNotEmpty()) {
                    Button(onClick = { HoldStore.releaseAll() }, modifier = Modifier.fillMaxWidth()) { Text("Release all") }
                }
                Hint("The ThumbShade quick-settings tile also releases everything held.")
            }
        }
        item {
            Section("What rules did") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${log.size} entries", Modifier.weight(1f))
                    TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide" else "Show") }
                    TextButton(onClick = { RuleLog.clear() }) { Text("Clear") }
                }
                if (showLog) {
                    log.take(60).forEach { e -> Hint(clockTime(e.time) + "  " + e.text) }
                }
            }
        }
    }

    if (templates) {
        AlertDialog(
            onDismissRequest = { templates = false },
            title = { Text("Start from a template") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(Templates.all) { t ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            TextButton(onClick = {
                                templates = false
                                editing = t.rule.copy(id = UUID.randomUUID().toString())
                            }) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(t.title, fontWeight = FontWeight.SemiBold)
                                    Hint(t.description)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { templates = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun RuleCard(rule: Rule, first: Boolean, last: Boolean, onEdit: () -> Unit) {
    Card(
        onClick = onEdit,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                IconButton(onClick = { RuleStore.move(rule.id, -1) }, enabled = !first) { Icon(Icons.Filled.ArrowUpward, "Move up") }
                IconButton(onClick = { RuleStore.move(rule.id, 1) }, enabled = !last) { Icon(Icons.Filled.ArrowDownward, "Move down") }
            }
            Column(Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val n = rule.root.size
                Hint("${rule.scopeLabel} · $n condition${if (n == 1) "" else "s"} · ${rule.actions.joinToString { it.type.label.substringBefore(' ') + "…" }}")
            }
            IconButton(onClick = { RuleStore.duplicate(rule.id) }) { Icon(Icons.Filled.ContentCopy, "Duplicate") }
            IconButton(onClick = { RuleStore.delete(rule.id) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
            Switch(checked = rule.enabled, onCheckedChange = { on -> RuleStore.upsert(rule.copy(enabled = on)) })
        }
    }
}

@Composable
private fun RuleEditor(modifier: Modifier, initial: Rule, onCancel: () -> Unit, onSave: (Rule) -> Unit) {
    var rule by remember(initial.id) { mutableStateOf(initial) }
    BackHandler(onBack = onCancel)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel") }
            Text("Edit rule", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { onSave(rule) }) { Text("Save") }
        }
        Section("Rule") {
            TextRow("Name", rule.name) { rule = rule.copy(name = it) }
            SwitchRow("Enabled", rule.enabled) { rule = rule.copy(enabled = it) }
            SwitchRow("Stop here when it matches", rule.stopAfterMatch, "Rules below won't run for this notification") { rule = rule.copy(stopAfterMatch = it) }
        }
        Section("Which apps") {
            AppSetRow(if (rule.apps.isEmpty()) "Any app (tap to limit)" else "Only these apps", rule.apps) { rule = rule.copy(apps = it) }
            AppSetRow("Never these apps", rule.excludeApps) { rule = rule.copy(excludeApps = it) }
        }
        Section("When") {
            GroupEditor(rule.root, depth = 0) { rule = rule.copy(root = it) }
        }
        Section("Then") {
            rule.actions.forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                ActionEditor(a, onChange = { na -> rule = rule.copy(actions = rule.actions.mapIndexed { j, x -> if (j == i) na else x }) }) {
                    rule = rule.copy(actions = rule.actions.filterIndexed { j, _ -> j != i })
                }
            }
            OutlinedButton(onClick = { rule = rule.copy(actions = rule.actions + RuleAction()) }, modifier = Modifier.fillMaxWidth()) { Text("Add action") }
        }
        Spacer(Modifier.heightIn(min = 40.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupEditor(group: CondGroup, depth: Int, onChange: (CondGroup) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = group.matchAll, onClick = { onChange(group.copy(matchAll = true)) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("All of these") }
            SegmentedButton(selected = !group.matchAll, onClick = { onChange(group.copy(matchAll = false)) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Any of these") }
        }
        if (depth > 0) SwitchRow("Invert this group (NOT)", group.negate) { onChange(group.copy(negate = it)) }
        if (group.conditions.isEmpty() && group.groups.isEmpty()) Hint("No conditions: matches every notification from the chosen apps.")
        group.conditions.forEachIndexed { i, c ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    CondEditor(c, onChange = { nc -> onChange(group.copy(conditions = group.conditions.mapIndexed { j, x -> if (j == i) nc else x })) }) {
                        onChange(group.copy(conditions = group.conditions.filterIndexed { j, _ -> j != i }))
                    }
                }
            }
        }
        group.groups.forEachIndexed { i, g ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Group", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onChange(group.copy(groups = group.groups.filterIndexed { j, _ -> j != i })) }) { Icon(Icons.Filled.Delete, "Remove group") }
                    }
                    GroupEditor(g, depth + 1) { ng -> onChange(group.copy(groups = group.groups.mapIndexed { j, x -> if (j == i) ng else x })) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onChange(group.copy(conditions = group.conditions + Cond())) }, modifier = Modifier.weight(1f)) { Text("Add condition") }
            if (depth < 2) {
                OutlinedButton(onClick = { onChange(group.copy(groups = group.groups + CondGroup(matchAll = false))) }, modifier = Modifier.weight(1f)) { Text("Add group") }
            }
        }
    }
}

private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CondEditor(c: Cond, onChange: (Cond) -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ChoiceRow("Condition", CondType.entries, c.type, { it.label }) { onChange(c.copy(type = it)) }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Remove condition") }
    }
    SwitchRow("Invert (NOT)", c.negate) { onChange(c.copy(negate = it)) }
    when (c.type) {
        CondType.TEXT -> {
            ChoiceRow("Look in", TextField.entries, c.field, { it.label }) { onChange(c.copy(field = it)) }
            ChoiceRow("Match", WordMode.entries, c.wordMode, { it.label }) { onChange(c.copy(wordMode = it)) }
            TextRow(
                if (c.wordMode == WordMode.REGEX) "Pattern" else "Words",
                c.words,
                when {
                    c.wordMode == WordMode.REGEX && !RuleMatcher.isValidRegex(c.words) -> "That pattern isn't valid"
                    c.wordMode == WordMode.REGEX -> "Java regular expression"
                    else -> "Separate alternatives with |"
                },
            ) { onChange(c.copy(words = it)) }
        }
        CondType.CATEGORY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Cat.entries.forEach { cat ->
                FilterChip(
                    selected = cat in c.categories,
                    onClick = { onChange(c.copy(categories = if (cat in c.categories) c.categories - cat else c.categories + cat)) },
                    label = { Text(cat.label) },
                )
            }
        }
        CondType.IMPORTANCE -> {
            Text("Importance ${c.minImportance} to ${c.maxImportance} (1 = min, 3 = default, 4 = high, 5 = max)")
            RangeSlider(
                value = c.minImportance.toFloat()..c.maxImportance.toFloat(),
                onValueChange = { r -> onChange(c.copy(minImportance = r.start.roundToInt(), maxImportance = r.endInclusive.roundToInt())) },
                valueRange = 0f..5f,
                steps = 4,
            )
        }
        CondType.FLAG -> ChoiceRow("Kind", Flag.entries, c.flag, { it.label }) { onChange(c.copy(flag = it)) }
        CondType.TIME -> {
            SliderRow("From", c.startMinute.toFloat(), 0f..1425f, steps = 94, format = { Schedule.formatMinute(it.roundToInt()) }) {
                onChange(c.copy(startMinute = (it / 15).roundToInt() * 15))
            }
            SliderRow("Until", c.endMinute.toFloat(), 0f..1440f, steps = 95, format = { Schedule.formatMinute(it.roundToInt() % 1440) }) {
                onChange(c.copy(endMinute = (it / 15).roundToInt() * 15))
            }
            if (c.endMinute < c.startMinute) Hint("Runs past midnight into the next day.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dayNames.forEachIndexed { i, name ->
                    val day = i + 1
                    FilterChip(
                        selected = day in c.days,
                        onClick = { onChange(c.copy(days = if (day in c.days) c.days - day else c.days + day)) },
                        label = { Text(name) },
                    )
                }
            }
        }
        CondType.LENGTH -> {
            Text("Title + text length ${c.minLength} to ${if (c.maxLength >= 1000) "any" else c.maxLength} characters")
            RangeSlider(
                value = c.minLength.toFloat()..c.maxLength.coerceAtMost(1000).toFloat(),
                onValueChange = { r ->
                    onChange(c.copy(minLength = r.start.roundToInt(), maxLength = if (r.endInclusive >= 1000f) 100_000 else r.endInclusive.roundToInt()))
                },
                valueRange = 0f..1000f,
            )
        }
        CondType.DEVICE -> ChoiceRow("When", DeviceAspect.entries, c.device, { it.label }) { onChange(c.copy(device = it)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionEditor(a: RuleAction, onChange: (RuleAction) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            onChange(a.copy(soundUri = uri?.toString().orEmpty()))
        }
    }
    var addingTime by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ChoiceRow("Action", ActionType.entries, a.type, { it.label }) { onChange(a.copy(type = it)) }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Remove action") }
    }
    when (a.type) {
        ActionType.MUTE -> Hint("No sound or vibration for this notification; it still appears in the shade.")
        ActionType.DISMISS -> Hint("Removes the notification as it arrives (permanent ones can't be dismissed).")
        ActionType.OPEN -> Hint("Opens the notification as if you tapped it.")
        ActionType.COPY_CODE -> Hint("Finds a one-time code in the text and copies it to the clipboard.")
        ActionType.SNOOZE, ActionType.REMIND -> SliderRow("Minutes", a.minutes.toFloat(), 1f..480f, format = { "${it.roundToInt()} min" }) { onChange(a.copy(minutes = it.roundToInt())) }
        ActionType.FIRST_THEN_QUIET -> {
            SliderRow("Quiet window", a.minutes.toFloat(), 1f..120f, format = { "${it.roundToInt()} min" }) { onChange(a.copy(minutes = it.roundToInt())) }
            SwitchRow("Dismiss the rest instead of silencing", a.quietDismiss) { onChange(a.copy(quietDismiss = it)) }
        }
        ActionType.HOLD -> {
            ChoiceRow("Deliver", BatchMode.entries, a.batchMode, { it.label }) { onChange(a.copy(batchMode = it)) }
            if (a.batchMode == BatchMode.TIMES) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.batchTimes.sorted().forEach { m ->
                        InputChip(
                            selected = false,
                            onClick = { onChange(a.copy(batchTimes = a.batchTimes - m)) },
                            label = { Text(Schedule.formatMinute(m)) },
                            trailingIcon = { Icon(Icons.Filled.Close, "Remove") },
                        )
                    }
                    TextButton(onClick = { addingTime = true }) { Text("Add time") }
                }
            } else {
                SliderRow("Every", a.intervalMinutes.toFloat(), 15f..360f, steps = 22, format = { "${it.roundToInt()} min" }) { onChange(a.copy(intervalMinutes = (it / 15).roundToInt() * 15)) }
            }
            Hint("Held notifications come back on their own at delivery time. Release them early from the Rules tab or the quick-settings tile.")
        }
        ActionType.CUSTOM_ALERT -> {
            ButtonRow("Sound", if (a.soundUri.isBlank()) "Default notification sound" else RingtoneManager.getRingtone(context, Uri.parse(a.soundUri))?.getTitle(context) ?: a.soundUri) {
                soundPicker.launch(
                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                )
            }
            ChoiceRow("Vibration", Vibe.entries, a.vibe, { it.label }) { onChange(a.copy(vibe = it)) }
            TextButton(onClick = { Effects.alert(context, a.soundUri, a.vibe) }) { Text("Test") }
        }
        ActionType.TORCH -> {
            SliderRow("Flashes", a.flashes.toFloat(), 1f..10f, steps = 8) { onChange(a.copy(flashes = it.roundToInt())) }
            TextButton(onClick = { Effects.flashTorch(context, a.flashes) }) { Text("Test") }
        }
        ActionType.SET_RINGER -> ChoiceRow("Ringer", RingerChoice.entries, a.ringer, { it.label }) { onChange(a.copy(ringer = it)) }
        ActionType.SET_DND -> SwitchRow("Turn Do Not Disturb on (off when unchecked)", a.dndOn) { onChange(a.copy(dndOn = it)) }
        ActionType.SPEAK -> TextRow("What to say (optional)", a.text, "Leave empty to read app, title and text. You can use {app}, {title}, {text}.") { onChange(a.copy(text = it)) }
        ActionType.PRESS_BUTTON -> TextRow("Button label contains", a.text, "e.g. Mark as read, Archive, Like") { onChange(a.copy(text = it)) }
        ActionType.REPLY -> TextRow("Reply text", a.text) { onChange(a.copy(text = it)) }
        ActionType.EDGE_LIGHT -> {
            ChoiceRow(
                "Effect",
                EdgeStyle.entries.map { it.name },
                a.edgeStyle,
                { n -> EdgeStyle.entries.firstOrNull { it.name == n }?.let { if (it.aroundButton) "${it.label} (around the button)" else it.label } ?: n },
            ) { onChange(a.copy(edgeStyle = it)) }
            ColorRow("Colour", a.color) { onChange(a.copy(color = it)) }
        }
    }

    if (addingTime) {
        var minute by remember { mutableStateOf(12 * 60f) }
        AlertDialog(
            onDismissRequest = { addingTime = false },
            title = { Text("Delivery time") },
            text = {
                SliderRow("Time", minute, 0f..1425f, steps = 94, format = { Schedule.formatMinute(it.roundToInt()) }) { minute = it }
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = (minute / 15).roundToInt() * 15
                    onChange(a.copy(batchTimes = (a.batchTimes + m).distinct().sorted()))
                    addingTime = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addingTime = false }) { Text("Cancel") } },
        )
    }
}
