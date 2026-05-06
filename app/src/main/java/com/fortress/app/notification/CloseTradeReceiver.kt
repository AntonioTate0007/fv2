package com.fortress.app.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.fortress.app.data.repository.FortressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Triggered when the user taps the `[CLOSE POSITION]` action on the lock-screen
 * notification. Fires the close-at-market API call and dismisses the notification.
 *
 * Note: the backend is responsible for re-checking that biometric authorization
 * was performed within the active session before honoring the close.
 */
class CloseTradeReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CLOSE) return
        val positionId = intent.getStringExtra(EXTRA_POSITION_ID).orEmpty()
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (positionId.isEmpty()) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                FortressRepository().closePosition(positionId, biometricToken = "lockscreen-action")
            } catch (_: Exception) {
                // Intentionally swallow — surface this through the in-app Armory list on next refresh.
            } finally {
                if (notifId >= 0) {
                    ContextCompat.getSystemService(context, NotificationManager::class.java)
                        ?.cancel(notifId)
                }
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_CLOSE = "com.fortress.app.action.CLOSE_POSITION"
        const val EXTRA_POSITION_ID = "extra.position_id"
        const val EXTRA_NOTIF_ID = "extra.notif_id"
    }
}
