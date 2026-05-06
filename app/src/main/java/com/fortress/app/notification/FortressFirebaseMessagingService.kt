package com.fortress.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fortress.app.MainActivity
import com.fortress.app.R
import com.fortress.app.data.api.ApiClient
import com.fortress.app.data.api.FcmTokenRequest
import com.fortress.app.data.repository.FortressRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives FCM pushes from the backend. The trading server fires one of these when an
 * open position hits the 50% profit-take rule; the resulting notification carries a
 * `[CLOSE POSITION]` action button the user can tap directly from the lock screen.
 */
class FortressFirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        registerToken(token)
    }

    private fun registerToken(token: String) {
        if (FortressRepository.USE_MOCK_DATA) return
        scope.launch {
            runCatching { ApiClient.service.registerFcmToken(FcmTokenRequest(token)) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val type = data["type"].orEmpty()
        if (type != TYPE_PROFIT_TARGET) return

        val positionId = data["positionId"].orEmpty()
        val ticker = data["ticker"].orEmpty()
        val profitPct = data["profitPct"]?.toDoubleOrNull() ?: 0.5
        val premium = data["currentPremium"].orEmpty()

        ensureChannel(this)
        notify(this, positionId, ticker, profitPct, premium)
    }

    companion object {
        const val CHANNEL_ID = "fortress.profit_alerts"
        const val TYPE_PROFIT_TARGET = "profit_target"
        private const val NOTIF_ID_BASE = 1_000

        fun registerCurrentToken(token: String) {
            if (FortressRepository.USE_MOCK_DATA) return
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { ApiClient.service.registerFcmToken(FcmTokenRequest(token)) }
            }
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.channel_profit_alerts),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = ctx.getString(R.string.channel_profit_alerts_desc)
                }
            )
        }

        fun notify(
            ctx: Context,
            positionId: String,
            ticker: String,
            profitPct: Double,
            premium: String
        ) {
            val tapIntent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("positionId", positionId)
            }
            val tapPi = PendingIntent.getActivity(
                ctx, positionId.hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val closeIntent = Intent(ctx, CloseTradeReceiver::class.java).apply {
                action = CloseTradeReceiver.ACTION_CLOSE
                putExtra(CloseTradeReceiver.EXTRA_POSITION_ID, positionId)
                putExtra(CloseTradeReceiver.EXTRA_NOTIF_ID, NOTIF_ID_BASE + positionId.hashCode())
            }
            val closePi = PendingIntent.getBroadcast(
                ctx, positionId.hashCode() + 1, closeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val pct = (profitPct * 100).toInt()
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("$ticker hit ${pct}% profit")
                .setContentText("Current premium $premium — tap to close at market.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(tapPi)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    ctx.getString(R.string.action_close_position),
                    closePi
                )
                .setAutoCancel(true)
                .build()

            ContextCompat.getSystemService(ctx, NotificationManager::class.java)
                ?.notify(NOTIF_ID_BASE + positionId.hashCode(), notification)
        }
    }
}
