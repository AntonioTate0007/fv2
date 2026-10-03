package com.thumbshade.app.rules

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.ContactsContract
import com.thumbshade.app.notif.Extract
import com.thumbshade.app.notif.NotifOps
import com.thumbshade.app.notif.NotificationRepo
import com.thumbshade.app.notif.ShadeItem
import com.thumbshade.app.notif.ShadeListenerService
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

object RuleEngine {
    data class Outcome(
        val removed: Boolean = false,
        val silenced: Boolean = false,
        val edgeStyle: String? = null,
        val edgeColor: Long? = null,
    )

    private val cooldowns = ConcurrentHashMap<String, Long>()

    fun onPosted(context: Context, item: ShadeItem): Outcome {
        val rules = RuleStore.rules.value.filter { it.enabled }
        if (rules.isEmpty()) return Outcome()
        val device = snapshot(context)
        val facts = item.facts(fromContact = fromContact(context, item))

        var outcome = Outcome()
        for (rule in rules) {
            if (!RuleMatcher.matches(rule, facts, device)) continue
            var terminal = false
            for (action in rule.actions) {
                val result = runCatching { run(context, rule, action, item) }.getOrDefault(Outcome())
                outcome = Outcome(
                    removed = outcome.removed || result.removed,
                    silenced = outcome.silenced || result.silenced,
                    edgeStyle = result.edgeStyle ?: outcome.edgeStyle,
                    edgeColor = result.edgeColor ?: outcome.edgeColor,
                )
                if (action.type.terminal && result.removed) terminal = true
            }
            if (terminal || rule.stopAfterMatch) break
        }
        return outcome
    }

    private fun log(rule: Rule, item: ShadeItem, what: String) {
        val label = item.title.ifBlank { item.appName }
        RuleLog.add("${rule.name}: $what \"$label\" (${item.appName})")
    }

    private fun run(context: Context, rule: Rule, a: RuleAction, item: ShadeItem): Outcome = when (a.type) {
        ActionType.MUTE -> {
            val ok = MuteController.mute(context, item)
            log(rule, item, if (ok) "silenced" else "could not silence (cooling down)")
            Outcome(silenced = ok)
        }
        ActionType.SNOOZE -> {
            val ok = HoldStore.hold(item, System.currentTimeMillis() + a.minutes * 60_000L, HoldStore.Reason.SNOOZE)
            log(rule, item, "snoozed for ${a.minutes} min")
            Outcome(removed = ok, silenced = ok)
        }
        ActionType.DISMISS -> {
            ShadeListenerService.cancel(item.key)
            log(rule, item, "dismissed")
            Outcome(removed = item.clearable, silenced = true)
        }
        ActionType.HOLD -> {
            val at = Schedule.nextRelease(a, Instant.now(), ZoneId.systemDefault())
            val ok = HoldStore.hold(item, at.toEpochMilli(), HoldStore.Reason.HOLD)
            log(rule, item, "held until ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at.toEpochMilli()))}")
            Outcome(removed = ok, silenced = ok)
        }
        ActionType.FIRST_THEN_QUIET -> {
            val bucket = rule.id + "|" + item.pkg + "|" + item.conversationTitle.ifBlank { item.title }
            val now = System.currentTimeMillis()
            val last = cooldowns[bucket]
            cooldowns[bucket] = now
            if (last != null && now - last < a.minutes * 60_000L) {
                if (a.quietDismiss) {
                    ShadeListenerService.cancel(item.key)
                    log(rule, item, "dismissed (busy)")
                    Outcome(removed = true, silenced = true)
                } else {
                    val ok = MuteController.mute(context, item)
                    log(rule, item, "kept quiet (busy)")
                    Outcome(silenced = ok)
                }
            } else Outcome()
        }
        ActionType.REMIND -> {
            Reminders.schedule(context, item, a.minutes)
            log(rule, item, "will remind in ${a.minutes} min")
            Outcome()
        }
        ActionType.CUSTOM_ALERT -> {
            MuteController.mute(context, item)
            Effects.alert(context, a.soundUri, a.vibe)
            log(rule, item, "alerted")
            Outcome(silenced = false)
        }
        ActionType.TORCH -> {
            Effects.flashTorch(context, a.flashes)
            Outcome()
        }
        ActionType.SET_RINGER -> {
            Effects.setRinger(context, a.ringer)
            log(rule, item, "set ringer to ${a.ringer.label}")
            Outcome()
        }
        ActionType.SET_DND -> {
            ShadeListenerService.setInterruptionFilter(
                if (a.dndOn) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            log(rule, item, if (a.dndOn) "turned Do Not Disturb on" else "turned Do Not Disturb off")
            Outcome()
        }
        ActionType.SPEAK -> {
            val sentence = if (a.text.isNotBlank()) {
                a.text.replace("{app}", item.appName).replace("{title}", item.title).replace("{text}", item.displayText)
            } else listOf(item.appName, item.title, item.displayText).filter { it.isNotBlank() }.joinToString(". ")
            Effects.speak(context, sentence)
            Outcome()
        }
        ActionType.COPY_CODE -> {
            val code = Extract.code(item.allText)
            if (code != null) {
                NotifOps.copy(context, "Verification code", code)
                Effects.toast(context, "Copied code $code")
                log(rule, item, "copied code $code from")
            }
            Outcome()
        }
        ActionType.PRESS_BUTTON -> {
            val action = item.actions.firstOrNull { it.title.contains(a.text, ignoreCase = true) && a.text.isNotBlank() }
            if (action != null) {
                NotifOps.press(context, action)
                log(rule, item, "pressed \"${action.title}\" on")
            }
            Outcome()
        }
        ActionType.REPLY -> {
            val reply = item.replyAction
            if (reply != null && a.text.isNotBlank()) {
                NotifOps.reply(context, reply, a.text)
                log(rule, item, "replied to")
            }
            Outcome()
        }
        ActionType.OPEN -> {
            NotifOps.open(context, item)
            log(rule, item, "opened")
            Outcome()
        }
        ActionType.EDGE_LIGHT -> Outcome(edgeStyle = a.edgeStyle, edgeColor = a.color)
    }

    fun snapshot(context: Context): DeviceSnapshot {
        val pm = context.getSystemService(PowerManager::class.java)
        val am = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val now = ZonedDateTime.now()
        return DeviceSnapshot(
            screenOn = pm?.isInteractive ?: true,
            inCall = am?.mode == AudioManager.MODE_IN_CALL || am?.mode == AudioManager.MODE_IN_COMMUNICATION,
            ringerMode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL,
            dndOn = (nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL) > NotificationManager.INTERRUPTION_FILTER_ALL,
            charging = plugged,
            dayOfWeek = now.dayOfWeek.value,
            minuteOfDay = now.hour * 60 + now.minute,
        )
    }

    /** True when one of the notification's people is in the user's contacts. */
    fun fromContact(context: Context, item: ShadeItem): Boolean {
        if (item.people.isEmpty()) return false
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        return item.people.any { person ->
            val uri = person.uri ?: return@any false
            runCatching {
                when {
                    uri.startsWith("tel:") -> {
                        val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(uri.removePrefix("tel:")))
                        context.contentResolver.query(lookup, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
                    }
                    uri.startsWith("mailto:") -> {
                        val lookup = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, Uri.encode(uri.removePrefix("mailto:")))
                        context.contentResolver.query(lookup, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { it.count > 0 } ?: false
                    }
                    uri.startsWith("content://com.android.contacts") -> true
                    else -> false
                }
            }.getOrDefault(false)
        }
    }
}

/** "Remind me later": fires an alarm and re-alerts if the notification is still there. */
object Reminders {
    const val ACTION = "com.thumbshade.app.REMIND"

    fun schedule(context: Context, item: ShadeItem, minutes: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, RulesAlarmReceiver::class.java)
            .setAction(ACTION)
            .putExtra("key", item.key)
            .putExtra("title", item.title)
            .putExtra("app", item.appName)
            .putExtra("pkg", item.pkg)
        val pi = PendingIntent.getBroadcast(context, item.key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = System.currentTimeMillis() + minutes * 60_000L
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun fire(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val stillThere = NotificationRepo.get(key) != null || HoldStore.held.value.any { it.key == key }
        if (!stillThere) return
        com.thumbshade.app.App.postReminder(
            context,
            intent.getStringExtra("app").orEmpty(),
            intent.getStringExtra("title").orEmpty(),
            intent.getStringExtra("pkg").orEmpty(),
        )
    }
}
