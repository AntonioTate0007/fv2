package com.thumbshade.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.notif.NotificationRepo
import com.thumbshade.app.overlay.OverlayService
import com.thumbshade.app.rules.HoldStore
import com.thumbshade.app.rules.RuleStore
import com.thumbshade.app.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        SettingsRepo.init(this)
        RuleStore.init(this)
        HoldStore.init(this)
        com.thumbshade.app.ai.Engagement.init(this)
        com.thumbshade.app.ai.Digest.init(this)
        createChannels()

        NotificationRepo.items.debounce(300).onEach { Widgets.updateAll(this) }.launchIn(scope)
        SettingsRepo.state.distinctUntilChangedBy { it.openShadeIcon }.onEach { applyLauncherAlias(it.openShadeIcon) }.launchIn(scope)
        SettingsRepo.state.distinctUntilChangedBy { it.serviceEnabled }.onEach {
            if (it.serviceEnabled) OverlayService.startIfEnabled(this) else OverlayService.stop(this)
        }.launchIn(scope)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Floating button", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the button and shade running"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "\"Remind me later\" from your rules"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DIGEST, "Smart digest", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "One summary when your held low-priority notifications come back"
            }
        )
    }

    private fun applyLauncherAlias(enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, "com.thumbshade.app.ui.OpenShadeAlias"), state, PackageManager.DONT_KILL_APP,
            )
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_DIGEST = "digest"

        fun postReminder(context: Context, app: String, title: String, pkg: String) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            val pi = launch?.let {
                PendingIntent.getActivity(context, pkg.hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val n = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_stat_shade)
                .setContentTitle("Reminder: $app")
                .setContentText(title.ifBlank { "You have an unread notification" })
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
            runCatching { nm?.notify(("remind:" + pkg + title).hashCode(), n) }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        OverlayService.startIfEnabled(context)
    }
}
