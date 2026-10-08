package com.thumbshade.app.access

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process

/**
 * Optional alternative to the accessibility service for knowing which app is in front (used by
 * the per-app button behaviour). Needs "Usage access"; polls lightly while the screen is on.
 */
object UsageWatcher {
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private const val INTERVAL = 1500L

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private val tick = object : Runnable {
        override fun run() {
            val c = context ?: return
            if (AssistService.instance == null && c.getSystemService(PowerManager::class.java)?.isInteractive == true && hasAccess(c)) {
                latestForeground(c)?.let { if (it != c.packageName) AssistService.reportForeground(it) }
            }
            main.postDelayed(this, INTERVAL)
        }
    }

    fun start(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        main.post(tick)
    }

    fun stop() {
        main.removeCallbacks(tick)
        context = null
    }

    private fun latestForeground(context: Context): String? = runCatching {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 60_000, now)
        val e = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = e.packageName
        }
        latest
    }.getOrNull()
}
