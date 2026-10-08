package com.thumbshade.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.thumbshade.app.access.AssistService
import com.thumbshade.app.data.AppJson
import com.thumbshade.app.data.Backup
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.notif.ShadeListenerService
import com.thumbshade.app.overlay.OverlayService
import com.thumbshade.app.rules.Effects
import com.thumbshade.app.rules.RuleStore

@Composable
fun GeneralScreen() {
    val context = LocalContext.current
    val s by SettingsRepo.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportBackup(context, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBackup(context, uri)
    }

    @Suppress("UNUSED_EXPRESSION") tick
    val listener = ShadeListenerService.isEnabled(context)
    val overlay = Settings.canDrawOverlays(context)
    val access = AssistService.isEnabled(context)
    val pm = context.getSystemService(PowerManager::class.java)
    val battery = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    val nm = context.getSystemService(NotificationManager::class.java)
    val dnd = nm?.isNotificationPolicyAccessGranted == true
    val postNotif = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val exact = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    val contacts = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    var crash by remember { mutableStateOf(com.thumbshade.app.CrashLog.read(context)) }
    crash?.let { report ->
        Section("ThumbShade crashed last time") {
            Hint("Copy this and send it to whoever is fixing the app.")
            Text(report.take(1500), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    com.thumbshade.app.notif.NotifOps.copy(context, "ThumbShade crash", report)
                    Effects.toast(context, "Copied")
                }, modifier = Modifier.weight(1f)) { Text("Copy") }
                OutlinedButton(onClick = {
                    com.thumbshade.app.CrashLog.clear(context)
                    crash = null
                }, modifier = Modifier.weight(1f)) { Text("Dismiss") }
            }
        }
    }

    Section("Permissions") {
        PermissionRow("Notification access", listener, required = true, "Lets ThumbShade show your notifications. Nothing leaves your phone.") {
            open(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        PermissionRow("Display over other apps", overlay, required = true, "Needed for the floating button and the shade.") {
            open(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            PermissionRow("Post notifications", postNotif, required = true, "For the running-service notice and rule reminders.") {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        PermissionRow("Usage access", com.thumbshade.app.access.UsageWatcher.hasAccess(context), required = false, "Optional: another way to know which app is open, for the per-app button behaviour, if you'd rather not turn on the accessibility service.") {
            open(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        PermissionRow("Accessibility service", access, required = false, "Optional: per-app button, keyboard-aware button, Back/Home/Recents gestures, pasting saved texts, pressing notification buttons. Reads no screen content.") {
            open(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        PermissionRow("Battery optimisation off", battery, required = false, "Some phones close background apps. dontkillmyapp.com explains your brand's extra settings.") {
            open(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
        }
        PermissionRow("Do Not Disturb access", dnd, required = false, "Optional: lets rules change the ringer.") {
            open(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            PermissionRow("Exact alarms", exact, required = false, "Optional: batches and reminders arrive on the minute.") {
                open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)))
            }
        }
        PermissionRow("Contacts", contacts, required = false, "Optional: for the \"from a saved contact\" rule condition.") {
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
        if (listener) {
            OutlinedButton(onClick = { ShadeListenerService.resync() }, modifier = Modifier.fillMaxWidth()) { Text("Refresh notifications") }
        }
    }

    Section("Debug messages") {
        Hint("Show the raw data of the notifications currently in the shade, for troubleshooting.")
        var debug by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { debug = true }, modifier = Modifier.fillMaxWidth()) { Text("Open debug messages") }
        if (debug) DebugDialog { debug = false }
    }

    Section("Service") {
        SwitchRow("Run the service", s.serviceEnabled, "Hosts the button and the shade") { on ->
            SettingsRepo.update { it.copy(serviceEnabled = on) }
            if (on) OverlayService.startIfEnabled(context) else OverlayService.stop(context)
        }
        SwitchRow("Show floating button", s.showButton) { on -> SettingsRepo.update { it.copy(showButton = on) } }
        SwitchRow("Hide the \"displaying over other apps\" notice", s.hideOverlayWarning, "Snoozes Android's reminder that ThumbShade draws over apps") { on ->
            SettingsRepo.update { it.copy(hideOverlayWarning = on) }
        }
        Button(onClick = {
            val svc = OverlayService.instance
            if (svc != null) svc.toggleShade() else context.startActivity(Intent(context, ShadeActivity::class.java))
        }, modifier = Modifier.fillMaxWidth()) { Text("Open the shade now") }
    }

    ThemeSection()

    IconsSection()

    Section("Lock screen") {
        SwitchRow("Show shade on lock screen", s.lockscreenShade, "Opening the shade while locked shows it over the lock screen") { on ->
            SettingsRepo.update { it.copy(lockscreenShade = on) }
        }
        SwitchRow("Add an \"Open shade\" app icon", s.openShadeIcon, "A second launcher icon that opens the shade. Point your launcher or Tasker / MacroDroid at it.") { on ->
            SettingsRepo.update { it.copy(openShadeIcon = on) }
        }
        if (s.lockscreenShade) {
            SwitchRow("Reply on lock screen", s.replyOnLock, "Reply to messages from the lock-screen shade without unlocking first; when off, replying asks you to unlock first") { on ->
                SettingsRepo.update { it.copy(replyOnLock = on) }
            }
            Text("Lock screen appearance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            SwitchRow("Hide notification content", s.lockHideContent, "Show only which app each notification is from until you unlock") { on ->
                SettingsRepo.update { it.copy(lockHideContent = on) }
            }
            SliderRow("Background darkness", s.lockDim, 0f..0.95f, format = { "${(it * 100).toInt()}%" }) { v ->
                SettingsRepo.update { it.copy(lockDim = v) }
            }
        }
        Hint("Widgets: long-press your home screen → Widgets → ThumbShade (list, icon strip, count).")
    }

    Section("Saved texts") {
        Hint("Paste these with a button gesture. One per line.")
        TextRow("Saved texts", s.savedTexts.joinToString("\n"), singleLine = false) { v ->
            SettingsRepo.update { it.copy(savedTexts = v.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() }) }
        }
    }

    Section("Backup and restore") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { exportLauncher.launch("thumbshade-backup.json") }, modifier = Modifier.weight(1f)) { Text("Export") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.weight(1f)) { Text("Import") }
        }
        Hint("Settings and rules, as a JSON file.")
    }

    Section("About") {
        val version = remember {
            runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                @Suppress("DEPRECATION")
                "Version ${info.versionName} (build ${info.versionCode})"
            }.getOrDefault("")
        }
        Text(version, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Hint("ThumbShade: notifications at the bottom of the screen, where your thumb is. Everything is free and stays on your phone; the app has no internet permission.")
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, required: Boolean, explain: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    when {
                        granted -> Color(0xFF00C853)
                        required -> Color(0xFFFF5252)
                        else -> Color(0xFF9E9E9E)
                    }
                ),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title + if (!required) " (optional)" else "", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Hint(if (granted) "Granted" else explain)
        }
    }
}

private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}

private fun exportBackup(context: Context, uri: Uri) {
    runCatching {
        val json = AppJson.encodeToString(Backup.serializer(), Backup(settings = SettingsRepo.current, rules = RuleStore.rules.value))
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        Effects.toast(context, "Exported")
    }.onFailure { Effects.toast(context, "Export failed: ${it.message}") }
}

private fun importBackup(context: Context, uri: Uri) {
    runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
        val backup = AppJson.decodeFromString(Backup.serializer(), text)
        // Keep this phone's button position.
        val current = SettingsRepo.current
        SettingsRepo.replace(backup.settings.copy(buttonXFrac = current.buttonXFrac, buttonYFrac = current.buttonYFrac, buttonDocked = current.buttonDocked, dockRight = current.dockRight))
        RuleStore.update { backup.rules }
        Effects.toast(context, "Imported ${backup.rules.size} rule(s)")
    }.onFailure { Effects.toast(context, "That file isn't a ThumbShade backup") }
}


/** Raw extras of every notification in the shade, to copy into a bug report. */
@Composable
private fun DebugDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dump = remember {
        com.thumbshade.app.notif.NotificationRepo.items.value.joinToString("\n\n") { item ->
            val n = item.sbn.notification
            buildString {
                append("== ").append(item.pkg).append("  ").append(item.key).append('\n')
                append("channel=").append(item.channelId).append(" importance=").append(item.importance)
                append(" category=").append(n.category).append(" flags=0x").append(Integer.toHexString(n.flags)).append('\n')
                append("group=").append(item.groupKey).append(" summary=").append(item.groupSummary)
                append(" ongoing=").append(item.ongoing).append(" customLayout=").append(item.customView != null).append('\n')
                n.extras?.let { ex ->
                    ex.keySet().sorted().forEach { k ->
                        @Suppress("DEPRECATION")
                        val v = runCatching { ex.get(k) }.getOrNull()
                        val text = when (v) {
                            is Array<*> -> "[" + v.size + " items]"
                            is CharSequence -> v.toString().take(300)
                            else -> v?.toString()?.take(120)
                        }
                        append(k).append(" = ").append(text).append('\n')
                    }
                }
                append("actions=").append(item.actions.joinToString { it.title })
            }
        }.ifBlank { "No notifications in the shade." }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Debug messages") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 480.dp)) {
                item {
                    Text(dump, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { com.thumbshade.app.notif.NotifOps.copy(context, "ThumbShade debug", dump) }) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
