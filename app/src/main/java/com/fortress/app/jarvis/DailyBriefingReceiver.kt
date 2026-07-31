package com.fortress.app.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires once a day: composes the briefing, posts it, speaks the summary, then arms
 * tomorrow's alarm so the daily cycle continues.
 */
class DailyBriefingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BRIEF) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val briefing = BriefingComposer.compose(context)
                JarvisNotifications.briefing(context, briefing.title, briefing.body)
                VoiceManager.get(context).speak(briefing.spoken)
            } finally {
                DailyBriefingScheduler.scheduleNext(context)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_BRIEF = "com.fortress.app.jarvis.DAILY_BRIEF"
    }
}
