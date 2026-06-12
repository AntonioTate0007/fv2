package com.fortress.app.jarvis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fortress.app.MainActivity

/**
 * Jarvis's own notification channels and posts — separate from the trading "Profit
 * Alerts" channel so the user can tune reminders and the morning briefing on their own.
 */
object JarvisNotifications {

    const val CHANNEL_REMINDERS = "jarvis.reminders"
    const val CHANNEL_BRIEFING = "jarvis.briefing"
    private const val REMINDER_NOTIF_BASE = 5_000
    private const val BRIEFING_NOTIF_ID = 4_100

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_REMINDERS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Jarvis reminders you've asked for" }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_BRIEFING) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_BRIEFING, "Daily Briefing", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Your morning rundown from Jarvis" }
            )
        }
    }

    fun reminder(context: Context, id: String, message: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Reminder")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context, id.hashCode()))
            .setAutoCancel(true)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.notify(REMINDER_NOTIF_BASE + id.hashCode(), notification)
    }

    fun briefing(context: Context, title: String, body: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_BRIEFING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context, BRIEFING_NOTIF_ID))
            .setAutoCancel(true)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.notify(BRIEFING_NOTIF_ID, notification)
    }

    private fun openApp(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("deeplink", "jarvis")
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
