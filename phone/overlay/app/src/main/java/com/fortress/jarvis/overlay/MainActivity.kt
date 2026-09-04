package com.fortress.jarvis.overlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

/** Setup screen: grant the two permissions, start the floating ring, test it. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var preview: ReactorView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        preview = findViewById(R.id.preview)
        preview.idleAlpha = 0.6f

        findViewById<Button>(R.id.btn_overlay_perm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btn_termux_perm).setOnClickListener {
            requestPermissions(arrayOf(Termux.PERMISSION), 1)
        }
        findViewById<Button>(R.id.btn_start).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                status.text = "Grant 'draw over other apps' first."
            } else {
                OverlayService.start(this)
                handler.postDelayed({ refresh() }, 400)
            }
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            OverlayService.stop(this)
            handler.postDelayed({ refresh() }, 400)
        }
        findViewById<Button>(R.id.btn_test).setOnClickListener { demo() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val termux = Termux.installed(this)
        val perm = Termux.permitted(this)
        val running = OverlayService.instance != null
        status.text = buildString {
            append(if (overlay) "✓" else "✗").append(" draw over other apps\n")
            append(if (termux) "✓" else "✗").append(" Termux installed\n")
            append(if (perm) "✓" else "✗").append(" run Termux commands\n")
            append(if (running) "● floating ring is ON" else "○ floating ring is off")
        }
    }

    /** Cycle the states on both the preview and the floating ring (if running). */
    private fun demo() {
        val seq = listOf(
            "listening" to "", "thinking" to "",
            "speaking" to "Battery is at 73%, discharging.", "idle" to ""
        )
        var delay = 0L
        for ((state, text) in seq) {
            handler.postDelayed({
                preview.state = when (state) {
                    "listening" -> ReactorView.State.LISTENING
                    "thinking" -> ReactorView.State.THINKING
                    "speaking" -> ReactorView.State.SPEAKING
                    else -> ReactorView.State.IDLE
                }
                if (text.isNotEmpty()) preview.caption = text
                OverlayService.instance?.applyState(state, text.ifEmpty { null })
            }, delay)
            delay += if (state == "speaking") 3500 else 1500
        }
    }
}
