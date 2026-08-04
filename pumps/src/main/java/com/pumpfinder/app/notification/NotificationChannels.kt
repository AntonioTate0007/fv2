package com.pumpfinder.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.pumpfinder.app.R

object NotificationChannels {
    const val PUMP_ALERTS = "pumps.pump_alerts"

    fun ensure(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(PUMP_ALERTS) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                PUMP_ALERTS,
                ctx.getString(R.string.channel_pump_alerts),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = ctx.getString(R.string.channel_pump_alerts_desc)
            }
        )
    }
}
