package com.fortress.app.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires when a reminder's alarm goes off: posts the notification, speaks it aloud,
 * then drops it from the store. Uses goAsync() so the short-lived receiver stays
 * alive long enough to finish the DataStore write.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty().ifBlank { "Reminder" }

        JarvisNotifications.reminder(context, id, message)
        // Best-effort spoken nudge — TTS queues the line until the engine is ready.
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
        const val ACTION_FIRE = "com.fortress.app.jarvis.REMINDER_FIRE"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_MESSAGE = "reminder_message"
    }
}
