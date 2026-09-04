package com.fortress.jarvis.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/**
 * Foreground service that keeps the reactor ring floating over every app.
 *
 * Touch: drag to move (snaps to the nearest side edge), tap to talk (runs the
 * Termux Jarvis shortcut), long-press to open settings.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var ring: ReactorView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private var hidden = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        addRing()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(StateReceiver.EXTRA_STATE)?.let {
            applyState(it, intent.getStringExtra(StateReceiver.EXTRA_TEXT))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeRing()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── state from Termux ─────────────────────────────────────────────────

    fun applyState(state: String, text: String?) {
        handler.post {
            when (state.lowercase()) {
                "hidden" -> setHidden(true)
                "shown" -> setHidden(false)
                "stop" -> stopSelf()
                else -> {
                    setHidden(false)
                    ring?.state = when (state.lowercase()) {
                        "listening" -> ReactorView.State.LISTENING
                        "thinking" -> ReactorView.State.THINKING
                        "speaking" -> ReactorView.State.SPEAKING
                        else -> ReactorView.State.IDLE
                    }
                    if (!text.isNullOrBlank()) ring?.caption = text
                }
            }
        }
    }

    private fun setHidden(h: Boolean) {
        hidden = h
        ring?.visibility = if (h) View.GONE else View.VISIBLE
    }

    // ── window management ─────────────────────────────────────────────────

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun addRing() {
        val view = ReactorView(this)
        val prefs = getSharedPreferences("overlay", MODE_PRIVATE)
        val w = dp(RING_DP)
        val h = dp(RING_DP) + view.captionHeight.toInt()
        params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", screenWidth() - w)
            y = prefs.getInt("y", screenHeight() / 3)
        }
        view.setOnTouchListener(DragTapListener())
        wm.addView(view, params)
        ring = view
    }

    private fun removeRing() {
        ring?.let { try { wm.removeView(it) } catch (e: Exception) { /* already gone */ } }
        ring = null
    }

    private fun screenWidth() = resources.displayMetrics.widthPixels
    private fun screenHeight() = resources.displayMetrics.heightPixels

    private fun snapToEdge() {
        val v = ring ?: return
        val targetX = if (params.x + params.width / 2 < screenWidth() / 2) 0 else screenWidth() - params.width
        val startX = params.x
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener {
                val f = it.animatedValue as Float
                params.x = (startX + (targetX - startX) * f).toInt()
                try { wm.updateViewLayout(v, params) } catch (e: Exception) { /* detached */ }
            }
            start()
        }
        params.y = params.y.coerceIn(0, screenHeight() - params.height)
        getSharedPreferences("overlay", MODE_PRIVATE).edit().putInt("x", targetX).putInt("y", params.y).apply()
    }

    private inner class DragTapListener : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var downAt = 0L
        private var moved = false
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private val longPress = Runnable {
            moved = true  // consume: no tap after a long press
            openSettings()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    downAt = System.currentTimeMillis(); moved = false
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                    if (moved) {
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(v, params) } catch (ex: Exception) { /* detached */ }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (moved) snapToEdge()
                    else if (e.actionMasked == MotionEvent.ACTION_UP) onTap()
                    return true
                }
            }
            return false
        }
    }

    private fun onTap() {
        ring?.state = ReactorView.State.LISTENING
        if (!Termux.run(this)) ring?.state = ReactorView.State.IDLE
        // Safety net: if Termux never reports back, don't stay lit forever.
        handler.postDelayed({ if (ring?.state == ReactorView.State.LISTENING) ring?.state = ReactorView.State.IDLE }, 20_000)
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ── notification ──────────────────────────────────────────────────────

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN)
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val talk = PendingIntent.getBroadcast(
            this, 1, Intent(this, StateReceiver::class.java).setAction(StateReceiver.ACTION_TALK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_talk), talk).build())
            .build()
    }

    companion object {
        const val CHANNEL = "jarvis_overlay"
        const val NOTIF_ID = 1
        const val RING_DP = 132f

        @Volatile var instance: OverlayService? = null

        fun intent(ctx: Context, state: String? = null, text: String? = null): Intent =
            Intent(ctx, OverlayService::class.java).apply {
                if (state != null) putExtra(StateReceiver.EXTRA_STATE, state)
                if (text != null) putExtra(StateReceiver.EXTRA_TEXT, text)
            }

        fun start(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent(ctx))
            else ctx.startService(intent(ctx))
        }

        fun stop(ctx: Context) { ctx.stopService(intent(ctx)) }
    }
}
