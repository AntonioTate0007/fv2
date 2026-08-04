package com.pumpfinder.app.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.app.NotificationManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pumpfinder.app.MainActivity
import com.pumpfinder.app.R
import com.pumpfinder.app.data.PumpRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives `type=pump_alert` data messages from the backend. The payload mirrors
 * what `_send_pump_alert` puts on the wire:
 *   ticker, name, score, price, changePct, relVol, yahooUrl
 */
class PumpFcmService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        scope.launch { PumpRepository(applicationContext).registerFcmToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != TYPE_PUMP_ALERT) return
        val ticker = data["ticker"].orEmpty()
        if (ticker.isBlank()) return
        val score = data["score"]?.toIntOrNull() ?: 0
        val price = data["price"].orEmpty()
        val changePct = data["changePct"].orEmpty()
        val relVol = data["relVol"].orEmpty()
        val yahooUrl = data["yahooUrl"] ?: "https://finance.yahoo.com/quote/$ticker"

        NotificationChannels.ensure(this)
        post(this, ticker, score, price, changePct, relVol, yahooUrl)
    }

    companion object {
        const val TYPE_PUMP_ALERT = "pump_alert"
        private const val NOTIF_ID_BASE = 2_000

        fun post(
            ctx: Context, ticker: String, score: Int,
            price: String, changePct: String, relVol: String, yahooUrl: String,
        ) {
            val tapIntent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("ticker", ticker)
            }
            val tapPi = PendingIntent.getActivity(
                ctx, ticker.hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val yahooIntent = Intent(Intent.ACTION_VIEW, Uri.parse(yahooUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val yahooPi = PendingIntent.getActivity(
                ctx, ticker.hashCode() + 1, yahooIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val sign = if (changePct.startsWith("-")) "" else "+"
            val notification = NotificationCompat.Builder(ctx, NotificationChannels.PUMP_ALERTS)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("$ticker · score $score")
                .setContentText("\$$price · $sign$changePct% · ${relVol}× vol — tap to open")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(tapPi)
                .addAction(
                    android.R.drawable.ic_menu_view,
                    "Open Yahoo",
                    yahooPi,
                )
                .setAutoCancel(true)
                .build()

            ContextCompat.getSystemService(ctx, NotificationManager::class.java)
                ?.notify(NOTIF_ID_BASE + ticker.hashCode(), notification)
        }
    }
}
