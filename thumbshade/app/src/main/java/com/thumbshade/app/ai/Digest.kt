package com.thumbshade.app.ai

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import com.thumbshade.app.App
import com.thumbshade.app.R
import com.thumbshade.app.data.AppJson
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.notif.ShadeItem
import com.thumbshade.app.rules.HoldStore
import com.thumbshade.app.rules.MuteController
import com.thumbshade.app.rules.RuleLog
import com.thumbshade.app.rules.Schedule
import com.thumbshade.app.ui.ShowShadeActivity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.ZoneId

/**
 * Focus batching: low-urgency notifications are held quietly and come back together at your
 * digest times, announced by one summary notification instead of many interruptions.
 */
object Digest {
    @Serializable
    data class Entry(val key: String, val pkg: String, val appName: String, val title: String, val at: Long)

    private const val ACTION = "com.thumbshade.app.DIGEST"
    private var prefs: SharedPreferences? = null
    private val serializer = ListSerializer(Entry.serializer())
    private var entries: List<Entry> = emptyList()

    fun init(context: Context) {
        val p = context.getSharedPreferences("digest", Context.MODE_PRIVATE)
        prefs = p
        entries = p.getString("json", null)?.let { runCatching { AppJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    }

    @Synchronized
    private fun save(list: List<Entry>) {
        entries = list
        prefs?.edit()?.putString("json", AppJson.encodeToString(serializer, list))?.apply()
    }

    fun nextTime(now: Instant = Instant.now()): Instant =
        Schedule.nextTime(SettingsRepo.current.ai.digestTimes, now, ZoneId.systemDefault())

    /**
     * Called for every new notification that rules didn't already handle. Returns true when it
     * was held for the digest.
     */
    fun consider(context: Context, item: ShadeItem, urgency: Urgency): Boolean {
        val ai = SettingsRepo.current.ai
        if (!ai.digest || item.ongoing || !item.clearable || item.pkg in ai.digestNeverApps) return false
        when (urgency) {
            Urgency.LOW -> {
                val at = nextTime().toEpochMilli()
                if (!HoldStore.hold(item, at, HoldStore.Reason.HOLD)) return false
                save(entries.filterNot { it.key == item.key } + Entry(item.key, item.pkg, item.appName, item.title, at))
                schedule(context, at)
                RuleLog.add("Smart digest: held \"${item.title.ifBlank { item.appName }}\" (${item.appName})")
                return true
            }
            Urgency.NORMAL -> if (ai.quietNormal) MuteController.mute(context, item)
            else -> Unit
        }
        return false
    }

    /** A digest item came back on time: keep it quiet, the summary notification speaks for it. */
    fun onReturned(context: Context, item: ShadeItem) {
        if (entries.any { it.key == item.key }) MuteController.mute(context, item)
    }

    private fun schedule(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, at.hashCode(), Intent(context, DigestReceiver::class.java).setAction(ACTION).putExtra("at", at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        // A few seconds after the release so the notifications are back when you look.
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 3_000, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 3_000, pi)
    }

    fun fire(context: Context) {
        val now = System.currentTimeMillis()
        val due = entries.filter { it.at <= now + 5_000 }
        if (due.isEmpty()) return
        save(entries - due.toSet())
        val briefs = due.map { Summaries.Brief(it.appName, it.title) }
        val apps = due.groupingBy { it.appName }.eachCount().entries.sortedByDescending { it.value }
        val text = apps.take(4).joinToString(", ") { if (it.value > 1) "${it.key} (${it.value})" else it.key } +
            if (apps.size > 4) " and ${apps.size - 4} more" else ""
        val lines = due.take(6).map { "${it.appName}: ${it.title}" }
        val open = PendingIntent.getActivity(
            context, 41, Intent(context, ShowShadeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val style = NotificationCompat.InboxStyle().also { s -> lines.forEach { s.addLine(it) } }
        if (due.size > lines.size) style.setSummaryText("+${due.size - lines.size} more")
        val n = NotificationCompat.Builder(context, App.CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_shade)
            .setContentTitle("Your digest: ${due.size} notification" + if (due.size == 1) "" else "s")
            .setContentText(text.ifBlank { Summaries.group("your apps", briefs) })
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(DIGEST_ID, n) }
    }

    const val DIGEST_ID = 4242
}

class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Digest.fire(context)
    }
}
