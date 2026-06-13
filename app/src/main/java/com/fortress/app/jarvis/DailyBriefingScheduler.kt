package com.fortress.app.jarvis

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * Schedules the once-a-day briefing alarm. We arm a single exact alarm for the next
 * occurrence of the configured hour; [DailyBriefingReceiver] re-arms the following day
 * after it fires, so the chain is self-sustaining (and re-armed on boot).
 */
object DailyBriefingScheduler {

    fun scheduleNext(context: Context) {
        val enabled = runBlocking { JarvisPrefs.briefingEnabled(context) }
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (!enabled) {
            am.cancel(pendingIntent(context))
            return
        }
        val hour = runBlocking { JarvisPrefs.briefingHour(context) }
        val next = nextOccurrence(hour)
        val pi = pendingIntent(context)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    private fun nextOccurrence(hour: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DailyBriefingReceiver::class.java)
            .apply { action = DailyBriefingReceiver.ACTION_BRIEF }
        return PendingIntent.getBroadcast(
            context, 7_700, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
