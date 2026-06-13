package com.fortress.app.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fortress.app.MainActivity
import com.fortress.app.data.model.ActivityItem

/**
 * Fires a local notification when Autopilot places trades — mirrors Autopilot's
 * "Your trades are complete / Your portfolio made trades" lock-screen alerts.
 */
object ProfitAlertManager {

    fun notify(context: Context, item: ActivityItem) {
        FortressFirebaseMessagingService.ensureChannel(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("deeplink", "activity")
        }
        val tapPi = PendingIntent.getActivity(
            context, item.id.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, FortressFirebaseMessagingService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("🤖 ${item.title}")
            .setContentText(item.subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.subtitle))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .build()

        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.notify(NOTIF_BASE + item.id.hashCode(), notification)
    }

    private const val NOTIF_BASE = 2_000
}
