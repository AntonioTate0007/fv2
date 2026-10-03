package com.thumbshade.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thumbshade.app.notif.AppInfoCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Hint(subtitle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    step: Float? = null,
    format: (Float) -> String = { it.roundToInt().toString() },
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    val span = range.endInclusive - range.start
    // The − / + buttons move by one unit for whole-number ranges, 1% otherwise.
    val unit = step ?: if (span > 2f) 1f else span / 100f
    fun nudge(direction: Int) {
        val next = (local + direction * unit).coerceIn(range.start, range.endInclusive)
        local = next
        onChange(next)
    }
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(format(local), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { nudge(-1) }, modifier = Modifier.size(36.dp)) { Text("−") }
            Slider(
                value = local.coerceIn(range.start, range.endInclusive),
                onValueChange = { local = it },
                onValueChangeFinished = { onChange(local) },
                valueRange = range,
                steps = steps,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            FilledTonalIconButton(onClick = { nudge(1) }, modifier = Modifier.size(36.dp)) { Text("+") }
        }
    }
}

/** A row that opens a single-choice dialog. */
@Composable
fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { open = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(label(selected), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(options) { o ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(o)
                                    open = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = o == selected, onClick = {
                                onSelect(o)
                                open = false
                            })
                            Text(label(o))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

val palette: List<Long> = listOf(
    0xFF4DD9C9, 0xFF00B0FF, 0xFF2979FF, 0xFF7C4DFF, 0xFFD500F9, 0xFFFF4081, 0xFFFF1744,
    0xFFFF9100, 0xFFFFEA00, 0xFF76FF03, 0xFF00E676, 0xFFFFFFFF, 0xFF9E9E9E, 0xFF202124, 0xFF000000,
)

@Composable
fun ColorRow(title: String, color: Long, allowAlpha: Boolean = false, onChange: (Long) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            palette.forEach { c ->
                val keepAlpha = if (allowAlpha) (color and 0xFF000000L) else 0xFF000000L
                val value = (c and 0x00FFFFFFL) or keepAlpha
                val selected = (color and 0x00FFFFFFL) == (c and 0x00FFFFFFL)
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f), CircleShape)
                        .clickable { onChange(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Filled.Check, null, tint = if (c == 0xFFFFFFFF) Color.Black else Color.White, modifier = Modifier.size(18.dp))
                }
            }
            OutlinedButton(onClick = { custom = true }) { Text("Hex") }
        }
        if (allowAlpha) {
            SliderRow("Opacity", ((color ushr 24) and 0xFF).toFloat() / 255f, 0.1f..1f, format = { "${(it * 100).roundToInt()}%" }) { a ->
                onChange((color and 0x00FFFFFFL) or (((a * 255).roundToInt().toLong() and 0xFF) shl 24))
            }
        }
    }
    if (custom) {
        var text by remember { mutableStateOf("%08X".format(color)) }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text(title) },
            text = { OutlinedTextField(text, { text = it.uppercase().filter { ch -> ch in "0123456789ABCDEF" }.take(8) }, label = { Text("AARRGGBB or RRGGBB") }) },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.toLongOrNull(16)
                    if (v != null) onChange(if (text.length <= 6) v or 0xFF000000L else v)
                    custom = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text("Cancel") } },
        )
    }
}

/** A colour that can be left automatic (0) or set by hand. */
@Composable
fun AutoColorRow(title: String, color: Long, onChange: (Long) -> Unit) {
    Column {
        SwitchRow(title, color == 0L, if (color == 0L) "Automatic" else "Custom") { auto -> onChange(if (auto) 0L else 0xFFFFFFFF) }
        if (color != 0L) ColorRow("$title (custom)", color, allowAlpha = true, onChange = onChange)
    }
}

@Composable
fun ButtonRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Multi-select app picker over launchable apps. */
@Composable
fun AppPickerDialog(title: String, selected: Set<String>, single: Boolean = false, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfoCache.AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(selected) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { AppInfoCache.launchableApps(context) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val list = apps
                if (list == null) {
                    Text("Loading apps…", Modifier.padding(16.dp))
                } else {
                    val filtered = list.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                        .sortedByDescending { it.pkg in chosen }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(filtered, key = { it.pkg }) { app ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (single) {
                                            onDone(setOf(app.pkg))
                                        } else {
                                            chosen = if (app.pkg in chosen) chosen - app.pkg else chosen + app.pkg
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(app.pkg, Modifier.size(32.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(app.label, Modifier.weight(1f))
                                if (!single) Checkbox(checked = app.pkg in chosen, onCheckedChange = null)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { if (!single) TextButton(onClick = { onDone(chosen) }) { Text("Done (${chosen.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A button that shows how many apps are chosen and opens the picker. */
@Composable
fun AppSetRow(title: String, apps: Set<String>, onChange: (Set<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ButtonRow(title, if (apps.isEmpty()) "None" else "Selected apps: ${apps.size}") { open = true }
    if (open) {
        AppPickerDialog(title, apps, onDismiss = { open = false }) {
            onChange(it)
            open = false
        }
    }
}

@Composable
fun TextRow(label: String, value: String, hint: String? = null, singleLine: Boolean = true, onChange: (String) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column {
        OutlinedTextField(
            value = local,
            onValueChange = {
                local = it
                onChange(it)
            },
            label = { Text(label) },
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth(),
        )
        if (hint != null) Hint(hint)
    }
}
