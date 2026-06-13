package com.mark39.assistant

import android.app.Application
import com.mark39.assistant.reminders.ReminderNotifications
import com.mark39.assistant.reminders.ReminderScheduler
import com.mark39.assistant.reminders.ReminderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MarkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ReminderNotifications.ensureChannel(this)
        // Re-arm any pending reminders (alarms don't survive a force-stop/clear).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            ReminderStore.pruneExpired(this@MarkApp)
            ReminderStore.all(this@MarkApp).forEach { ReminderScheduler.schedule(this@MarkApp, it) }
        }
    }
}
