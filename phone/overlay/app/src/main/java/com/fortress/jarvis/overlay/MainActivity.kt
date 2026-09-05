package com.fortress.jarvis.overlay

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Setup wizard + overlay controls.
 *
 *  1. install the Termux apps from F-Droid
 *  2. bootstrap Jarvis inside Termux (one paste — the only manual step)
 *  3. write settings into Termux and start the bot
 *  4. permissions + the floating ring
 */
class MainActivity : Activity() {

    private lateinit var preview: ReactorView
    private lateinit var statusApps: TextView
    private lateinit var statusBoot: TextView
    private lateinit var statusRing: TextView
    private lateinit var log: TextView
    private lateinit var btnInstall: Button
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("setup", Context.MODE_PRIVATE) }
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        preview.idleAlpha = 0.6f
        statusApps = findViewById(R.id.status_apps)
        statusBoot = findViewById(R.id.status_boot)
        statusRing = findViewById(R.id.status_ring)
        log = findViewById(R.id.log)
        btnInstall = findViewById(R.id.btn_install)

        // step 1
        btnInstall.setOnClickListener { installNext() }
        // step 2
        findViewById<Button>(R.id.btn_bootstrap).setOnClickListener { bootstrap() }
        // step 3
        findViewById<Button>(R.id.btn_save_config).setOnClickListener { saveConfig() }
        findViewById<Button>(R.id.btn_start_jarvis).setOnClickListener {
            if (Termux.run(this, "Jarvis-Start")) say("Starting Jarvis in Termux…")
        }
        findViewById<Button>(R.id.btn_doctor).setOnClickListener {
            if (Termux.run(this, "Jarvis-Doctor")) say("Doctor running — result arrives as a notification")
        }
        // step 4
        findViewById<Button>(R.id.btn_overlay_perm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btn_termux_perm).setOnClickListener {
            requestPermissions(arrayOf(Termux.PERMISSION), 1)
        }
        findViewById<Button>(R.id.btn_start).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) say("Grant 'draw over other apps' first.")
            else { OverlayService.start(this); handler.postDelayed({ refresh() }, 400) }
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            OverlayService.stop(this); handler.postDelayed({ refresh() }, 400)
        }
        findViewById<Button>(R.id.btn_test).setOnClickListener { demo() }

        restoreFields()
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

    // ── step 1: Termux apps ───────────────────────────────────────────────

    private fun installNext() {
        if (busy) return
        if (!Installer.canInstall(this)) {
            say("Allow this app to install apps, then come back.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val next = Installer.missing(this).firstOrNull()
        if (next == null) { say("All Termux apps are installed."); return }
        busy = true
        btnInstall.isEnabled = false
        logLine("Resolving ${next.label} on F-Droid…")
        Thread {
            try {
                val url = Installer.resolveUrl(next.id)
                ui { logLine("Downloading ${next.label}…") }
                val apk = Installer.download(this, url, next.id) { pct ->
                    ui { btnInstall.text = "Downloading ${next.label}… $pct%" }
                }
                ui {
                    logLine("Opening installer for ${next.label}. Tap Install, then return here for the next one.")
                    Installer.install(this, apk)
                }
            } catch (e: Exception) {
                ui { logLine("Download failed: ${e.message}. Install ${next.label} from f-droid.org instead.") }
            } finally {
                ui {
                    busy = false
                    btnInstall.isEnabled = true
                    btnInstall.text = getString(R.string.btn_install)
                }
            }
        }.start()
    }

    // ── step 2: bootstrap ─────────────────────────────────────────────────

    private fun bootstrap() {
        if (!Termux.installed(this)) { say("Install Termux first (step 1)."); return }
        val clip = getSystemService(ClipboardManager::class.java)
        clip.setPrimaryClip(ClipData.newPlainText("jarvis bootstrap", Termux.BOOTSTRAP))
        say("Command copied. In Termux: long-press → Paste → Enter.")
        logLine("Bootstrap command on clipboard:\n${Termux.BOOTSTRAP}")
        Termux.openTermux(this)
    }

    // ── step 3: settings ──────────────────────────────────────────────────

    private val fields = mapOf(
        R.id.edit_token to "TELEGRAM_BOT_TOKEN",
        R.id.edit_chat to "TELEGRAM_CHAT_ID",
        R.id.edit_gemini to "GEMINI_API_KEY",
        R.id.edit_fortress_url to "FORTRESS_URL",
        R.id.edit_fortress_token to "FORTRESS_API_TOKEN",
    )

    private fun saveConfig() {
        val args = fields.map { (id, key) -> "$key=${findViewById<EditText>(id).text.toString().trim()}" }
        // remember locally so the fields survive app restarts (device-private storage)
        val ed = prefs.edit()
        fields.forEach { (id, key) -> ed.putString("field.$key", findViewById<EditText>(id).text.toString()) }
        ed.apply()
        if (Termux.runPath(this, "${Termux.HOME}/fortress/phone/configure.sh", args)) {
            say("Settings sent to Termux.")
            logLine("configure.sh ← ${fields.values.joinToString(", ")}")
        }
    }

    private fun restoreFields() {
        fields.forEach { (id, key) ->
            prefs.getString("field.$key", null)?.let { findViewById<EditText>(id).setText(it) }
        }
    }

    // ── status ────────────────────────────────────────────────────────────

    private fun refresh() {
        statusApps.text = Installer.PACKAGES.joinToString("\n") { p ->
            val ok = Installer.installed(this, p.id)
            "${if (ok) "✓" else "✗"} ${p.label}${if (!p.required && !ok) "  (optional)" else ""}"
        } + "\n" + (if (Installer.canInstall(this)) "✓" else "✗") + " allowed to install apps"
        btnInstall.isEnabled = !busy && Installer.missing(this).isNotEmpty()

        val booted = prefs.getBoolean("bootstrapped", false)
        statusBoot.text = if (booted) "✓ Jarvis is installed in Termux" else "○ not bootstrapped yet"

        val overlay = Settings.canDrawOverlays(this)
        val perm = Termux.permitted(this)
        val running = OverlayService.instance != null
        statusRing.text = buildString {
            append(if (overlay) "✓" else "✗").append(" draw over other apps\n")
            append(if (perm) "✓" else "✗").append(" run Termux commands\n")
            append(if (running) "● floating ring is ON" else "○ floating ring is off")
        }
    }

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

    // ── helpers ───────────────────────────────────────────────────────────

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun say(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun logLine(line: String) {
        val cur = log.text.toString()
        log.text = (if (cur.isEmpty()) line else "$cur\n$line").lines().takeLast(12).joinToString("\n")
    }
}
