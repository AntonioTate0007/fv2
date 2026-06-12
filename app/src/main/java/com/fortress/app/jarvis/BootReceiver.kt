package com.fortress.app.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Alarms don't survive a reboot, so on boot we re-arm everything: each future
 * reminder and the daily briefing. Expired reminders are pruned in the same pass.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                JarvisNotifications.ensureChannels(context)
                ReminderStore.pruneExpired(context)
                ReminderStore.all(context).forEach { ReminderScheduler.schedule(context, it) }
                DailyBriefingScheduler.scheduleNext(context)
            } finally {
                pending.finish()
            }
        }
    }
}
