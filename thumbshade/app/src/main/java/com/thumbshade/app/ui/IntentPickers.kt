package com.thumbshade.app.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thumbshade.app.data.GestureAction
import com.thumbshade.app.data.GestureType
import com.thumbshade.app.overlay.GestureRunner
import com.thumbshade.app.rules.Effects

/** One activity of an app, and whether other apps are allowed to open it. */
private data class Screen(val info: ActivityInfo, val name: String, val openable: Boolean)

private fun screensOf(context: Context, pkg: String): List<Screen> {
    val pm = context.packageManager
    val launcher = pm.getLaunchIntentForPackage(pkg)?.component?.className
    val activities = runCatching {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities
    }.getOrNull().orEmpty()
    return activities.map { a ->
        val label = runCatching { a.loadLabel(pm).toString() }.getOrDefault("")
        val short = a.name.substringAfterLast('.')
        Screen(
            info = a,
            name = if (label.isNotBlank() && label != a.applicationInfo.loadLabel(pm).toString()) "$label ($short)" else short,
            openable = a.exported && a.permission == null && a.enabled,
        )
    }.sortedWith(compareBy({ !it.openable }, { it.info.name != launcher }, { it.name.lowercase() }))
}

/** Step two of "Open an app screen": the app's activities, with the ones others can't open marked. */
@Composable
fun AppScreenPicker(pkg: String, onDismiss: () -> Unit, onPick: (GestureAction) -> Unit) {
    val context = LocalContext.current
    val screens = remember(pkg) { screensOf(context, pkg) }
    val appName = remember(pkg) { com.thumbshade.app.notif.AppInfoCache.label(context, pkg) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$appName screens") },
        text = {
            Column {
                Hint("Screens marked \"locked\" can't be opened by other apps; Test shows whether one works.")
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(screens, key = { it.info.name }) { sc ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(GestureAction(GestureType.APP_SCREEN, ComponentName(pkg, sc.info.name).flattenToString(), "$appName: ${sc.name}")) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(sc.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (sc.openable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                                if (!sc.openable) Text("locked", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                            TextButton(onClick = {
                                if (GestureRunner.fire(context, GestureType.APP_SCREEN, ComponentName(pkg, sc.info.name).flattenToString()) == null) {
                                    Effects.toast(context, "Opened")
                                }
                            }) { Text("Test") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Lists apps that offer shortcuts (the classic "add a shortcut" screens: Tasker, MacroDroid, contacts, …). */
@Composable
fun ShortcutPicker(onDismiss: () -> Unit, onPick: (GestureAction) -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val makers = remember {
        pm.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT), 0)
            .map { it.activityInfo to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }
    }
    var pendingLabel by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        @Suppress("DEPRECATION")
        val target = data?.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
        if (result.resultCode == Activity.RESULT_OK && target != null) {
            @Suppress("DEPRECATION")
            val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: pendingLabel
            onPick(GestureAction(GestureType.SHORTCUT, target.toUri(Intent.URI_INTENT_SCHEME), name))
        } else if (result.resultCode == Activity.RESULT_OK) {
            Effects.toast(context, "That app only makes shortcuts for home screens")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run a shortcut") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                if (makers.isEmpty()) item { Hint("No installed app offers shortcuts.") }
                items(makers, key = { it.first.packageName + it.first.name }) { (info, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                pendingLabel = label
                                runCatching {
                                    launcher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(ComponentName(info.packageName, info.name)))
                                }.onFailure { Effects.toast(context, "Could not open it") }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(info.packageName, Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private val targets = listOf("activity" to "Activity", "broadcast" to "Broadcast", "service" to "Service")

/** Builds an intent from its parts; Test sends it before it is saved. */
@Composable
fun CustomIntentDialog(initial: GestureAction, onDismiss: () -> Unit, onPick: (GestureAction) -> Unit) {
    val context = LocalContext.current
    val parsed = remember(initial) {
        if (initial.type != GestureType.CUSTOM_INTENT || initial.arg.isBlank()) null
        else runCatching { Intent.parseUri(initial.arg.substringAfter(':'), Intent.URI_INTENT_SCHEME) }.getOrNull()
    }
    var target by remember { mutableStateOf(if (parsed != null) initial.arg.substringBefore(':') else "activity") }
    var action by remember { mutableStateOf(parsed?.action ?: "") }
    var data by remember { mutableStateOf(parsed?.dataString ?: "") }
    var pkg by remember { mutableStateOf(parsed?.`package` ?: parsed?.component?.packageName ?: "") }
    var cls by remember { mutableStateOf(parsed?.component?.className ?: "") }
    var extras by remember {
        mutableStateOf(parsed?.extras?.keySet()?.joinToString("\n") { k -> "$k=${parsed.extras?.get(k)}" } ?: "")
    }
    var name by remember { mutableStateOf(initial.label.takeIf { initial.type == GestureType.CUSTOM_INTENT } ?: "") }

    fun build(): String {
        val i = Intent()
        if (action.isNotBlank()) i.action = action.trim()
        if (data.isNotBlank()) i.data = android.net.Uri.parse(data.trim())
        if (cls.isNotBlank() && pkg.isNotBlank()) i.component = ComponentName(pkg.trim(), cls.trim().let { if (it.startsWith(".")) pkg.trim() + it else it })
        else if (pkg.isNotBlank()) i.`package` = pkg.trim()
        extras.lines().map { it.trim() }.filter { '=' in it }.forEach { line ->
            val k = line.substringBefore('=').trim()
            val v = line.substringAfter('=').trim()
            when {
                v == "true" || v == "false" -> i.putExtra(k, v.toBoolean())
                v.toIntOrNull() != null -> i.putExtra(k, v.toInt())
                else -> i.putExtra(k, v)
            }
        }
        return target + ":" + i.toUri(Intent.URI_INTENT_SCHEME)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom intent") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        targets.forEachIndexed { i, (key, label) ->
                            SegmentedButton(
                                selected = target == key,
                                onClick = { target = key },
                                shape = SegmentedButtonDefaults.itemShape(i, targets.size),
                            ) { Text(label) }
                        }
                    }
                }
                item { OutlinedTextField(name, { name = it }, label = { Text("Name (shown in settings)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(action, { action = it }, label = { Text("Action") }, placeholder = { Text("android.intent.action.VIEW") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(data, { data = it }, label = { Text("Data (URI)") }, placeholder = { Text("https://… or tel:…") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(pkg, { pkg = it }, label = { Text("Package") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(cls, { cls = it }, label = { Text("Class (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(extras, { extras = it }, label = { Text("Extras, one key=value per line") }, minLines = 2, modifier = Modifier.fillMaxWidth()) }
                item {
                    Hint("Tasker and MacroDroid can listen for a broadcast: choose Broadcast and type the action you set up there.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onPick(GestureAction(GestureType.CUSTOM_INTENT, build(), name.ifBlank { action.ifBlank { pkg }.substringAfterLast('.') }))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    if (GestureRunner.fire(context, GestureType.CUSTOM_INTENT, build()) == null) Effects.toast(context, "Sent")
                }) { Text("Test") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
