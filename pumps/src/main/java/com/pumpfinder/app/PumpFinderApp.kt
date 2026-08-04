package com.pumpfinder.app

import android.app.Application
import com.google.firebase.messaging.FirebaseMessaging
import com.pumpfinder.app.data.PumpRepository
import com.pumpfinder.app.notification.NotificationChannels
import com.pumpfinder.app.notification.PumpScanWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PumpFinderApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)

        // Local fallback: WorkManager polls every 15 min, fires native
        // notifications even when the backend FCM channel is asleep.
        PumpScanWorker.schedule(this)

        // Best-effort: if google-services.json was added at build time, grab
        // the current FCM token and register it with the backend. Without the
        // plugin this throws (FirebaseApp not initialised) and we swallow.
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    scope.launch { PumpRepository(applicationContext).registerFcmToken(token) }
                }
            }
        }
    }
}
