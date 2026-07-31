package com.mark39.assistant.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Alarms don't survive a reboot — re-arm every pending reminder on boot. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ReminderNotifications.ensureChannel(context)
                ReminderStore.pruneExpired(context)
                ReminderStore.all(context).forEach { ReminderScheduler.schedule(context, it) }
            } finally {
                pending.finish()
            }
        }
    }
}
