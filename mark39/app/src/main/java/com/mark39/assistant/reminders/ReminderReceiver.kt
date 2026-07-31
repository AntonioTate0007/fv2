package com.mark39.assistant.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mark39.assistant.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Fires when a reminder's alarm goes off: notifies, speaks it, drops it from the store. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty().ifBlank { "Reminder" }

        ReminderNotifications.show(context, id, message)
        VoiceManager.get(context).speak("Reminder. $message")

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (id.isNotBlank()) ReminderStore.remove(context, id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.mark39.assistant.REMINDER_FIRE"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_MESSAGE = "reminder_message"
    }
}
