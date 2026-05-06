package com.fortress.app.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fortress.app.MainActivity
import com.fortress.app.R
import com.fortress.app.data.model.ActivePosition

/**
 * Fires a local "time to sell" notification when a position hits the 50% profit target.
 * Separate from FCM — this runs entirely on-device from the Armory polling loop.
 */
object ProfitAlertManager {

    fun notify(context: Context, position: ActivePosition) {
        FortressFirebaseMessagingService.ensureChannel(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("positionId", position.id)
            putExtra("deeplink", "armory")
        }
        val tapPi = PendingIntent.getActivity(
            context, position.id.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val closeIntent = Intent(context, CloseTradeReceiver::class.java).apply {
            action = CloseTradeReceiver.ACTION_CLOSE
            putExtra(CloseTradeReceiver.EXTRA_POSITION_ID, position.id)
            putExtra(CloseTradeReceiver.EXTRA_NOTIF_ID, NOTIF_BASE + position.id.hashCode())
        }
        val closePi = PendingIntent.getBroadcast(
            context, position.id.hashCode() + 9000, closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pct = (position.profitFraction * 100).toInt()
        val credit = "$${"%.2f".format(position.entryPremium - position.currentPremium)}"

        val notification = NotificationCompat.Builder(context, FortressFirebaseMessagingService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("🔔 ${position.ticker} hit $pct% profit — time to close!")
            .setContentText("Locked in $credit. Tap to close at market.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("${position.strategyLabel} has captured $pct% of max profit ($credit). " +
                    "The Fortress rule: close now, reset the brick, collect again next week."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(tapPi)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_close_position),
                closePi
            )
            .setAutoCancel(true)
            .build()

        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.notify(NOTIF_BASE + position.id.hashCode(), notification)
    }

    private const val NOTIF_BASE = 2_000
}
