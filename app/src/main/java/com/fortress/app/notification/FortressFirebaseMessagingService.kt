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
        val id = data["id"].ifNullOrBlank { System.currentTimeMillis().toString() }
        val title = data["title"].ifNullOrBlank { "Autopilot update" }
        val body = data["body"].ifNullOrBlank { "Your portfolio made trades." }
        ensureChannel(this)
        notify(this, id, title, body)
    }

    private fun String?.ifNullOrBlank(fallback: () -> String): String =
        if (this.isNullOrBlank()) fallback() else this

    companion object {
        const val CHANNEL_ID = "fortress.trade_alerts"
        const val TYPE_TRADE = "trade"
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

        fun notify(ctx: Context, id: String, title: String, body: String) {
            val tapIntent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("deeplink", "activity")
            }
            val tapPi = PendingIntent.getActivity(
                ctx, id.hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("🤖 $title")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(tapPi)
                .setAutoCancel(true)
                .build()

            ContextCompat.getSystemService(ctx, NotificationManager::class.java)
                ?.notify(NOTIF_ID_BASE + id.hashCode(), notification)
        }
    }
}
