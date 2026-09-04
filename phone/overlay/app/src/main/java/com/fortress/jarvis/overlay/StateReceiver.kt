package com.fortress.jarvis.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Entry point for Termux. From the phone:
 *
 *   am broadcast -n com.fortress.jarvis.overlay/.StateReceiver \
 *      -a com.fortress.jarvis.overlay.STATE --es state speaking --es text "Battery is at 73%"
 *
 * state ∈ idle | listening | thinking | speaking | hidden | shown | stop
 */
class StateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TALK -> Termux.run(context)
            ACTION_STATE -> {
                val state = intent.getStringExtra(EXTRA_STATE) ?: return
                val text = intent.getStringExtra(EXTRA_TEXT)
                val running = OverlayService.instance
                if (running != null) {
                    running.applyState(state, text)
                } else if (state != "stop" && state != "hidden") {
                    // Not running yet: try to bring it up carrying the state. Android 12+
                    // may refuse a background start; the user then taps Start in the app.
                    try {
                        context.startForegroundService(OverlayService.intent(context, state, text))
                    } catch (e: Exception) {
                        // swallow: nothing useful to do from a receiver
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_STATE = "com.fortress.jarvis.overlay.STATE"
        const val ACTION_TALK = "com.fortress.jarvis.overlay.TALK"
        const val EXTRA_STATE = "state"
        const val EXTRA_TEXT = "text"
    }
}
