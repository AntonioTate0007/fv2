package com.fortress.app

import android.app.Application
import com.fortress.app.notification.FortressFirebaseMessagingService
import com.fortress.app.notification.ProfitWatchWorker
import com.google.firebase.messaging.FirebaseMessaging

class FortressApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FortressFirebaseMessagingService.ensureChannel(this)

        // Primary background path for profit-target alerts: WorkManager checks every
        // 15 minutes whether any open position crossed 50%, fires a local lock-screen
        // notification if so. Survives reboot, runs whether or not the app is open.
        ProfitWatchWorker.schedule(this)

        // Optional FCM fallback: if google-services.json is ever dropped in, Firebase
        // initializes and we register the device's token with the backend so it can
        // also push instantly. Without google-services.json this no-ops.
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    FortressFirebaseMessagingService.registerCurrentToken(token)
                }
            }
        }
    }
}
