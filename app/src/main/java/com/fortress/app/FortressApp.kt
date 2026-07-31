package com.fortress.app

import android.app.Application
import com.fortress.app.jarvis.DailyBriefingScheduler
import com.fortress.app.jarvis.JarvisNotifications
import com.fortress.app.jarvis.ReminderScheduler
import com.fortress.app.jarvis.ReminderStore
import com.fortress.app.notification.FortressFirebaseMessagingService
import com.fortress.app.notification.ProfitWatchWorker
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FortressApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FortressFirebaseMessagingService.ensureChannel(this)

        // Jarvis assistant: ensure its notification channels exist, re-arm any pending
        // reminders (alarms don't survive a force-stop/clear), and schedule the next
        // daily briefing. Done off the main thread since it touches DataStore.
        JarvisNotifications.ensureChannels(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            ReminderStore.pruneExpired(this@FortressApp)
            ReminderStore.all(this@FortressApp).forEach { ReminderScheduler.schedule(this@FortressApp, it) }
            DailyBriefingScheduler.scheduleNext(this@FortressApp)
        }

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
