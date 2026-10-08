package com.jarvis.glasses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Setup screen: permissions, linking the glasses, API keys, and the on/off switch. */
class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings
    private lateinit var statusView: TextView
    private lateinit var glassesView: TextView
    private lateinit var transcriptView: TextView

    private val phonePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        (application as JarvisApp).initWearables()
        watchGlasses()
        refreshGlasses()
    }

    private val glassesCameraPermission by lazy {
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val granted = result.getOrNull() == PermissionStatus.Granted
            toast(if (granted) "Glasses camera allowed" else "Glasses camera not allowed: ${result.errorOrNull()?.description ?: "denied"}")
            refreshGlasses()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        glassesCameraPermission // must register before STARTED
        setContentView(buildUi())
        handleMetaCallback(intent)

        lifecycleScope.launch {
            JarvisService.status.collectLatest { statusView.text = it.label }
        }
        lifecycleScope.launch {
            JarvisService.transcript.collectLatest { transcriptView.text = it.takeLast(12).joinToString("\n\n") }
        }
        if (missingPermissions().isEmpty()) watchGlasses()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleMetaCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshGlasses()
    }

    /** The Meta AI app sends you back here (jarvisglasses://…) after you approve Jarvis. */
    private fun handleMetaCallback(intent: Intent?) {
        if (intent?.data?.scheme != "jarvisglasses") return
        if (!(application as JarvisApp).initWearables()) return
        runCatching {
            Wearables.handleIntent(intent) { request -> request.continueRegistration(this) }
        }
        refreshGlasses()
    }

    private var watching = false
    private fun watchGlasses() {
        if (watching || !JarvisApp.wearablesReady) return
        watching = true
        lifecycleScope.launch { Wearables.registrationState.collectLatest { refreshGlasses() } }
        lifecycleScope.launch { Wearables.devices.collectLatest { refreshGlasses() } }
    }

    private fun refreshGlasses() {
        if (!::glassesView.isInitialized) return
        glassesView.text = when {
            missingPermissions().isNotEmpty() -> "Step 1: grant phone permissions."
            !JarvisApp.wearablesReady -> "Meta SDK not ready yet — grant Bluetooth permission."
            else -> {
                val reg = Wearables.registrationState.value
                val devices = Wearables.devices.value
                val names = devices.mapNotNull { Wearables.devicesMetadata[it]?.value?.name }.joinToString()
                when {
                    reg != RegistrationState.REGISTERED -> "Glasses: not linked ($reg). Tap \"Link glasses\"."
                    devices.isEmpty() -> "Glasses: linked, but not connected. Put them on / open the case."
                    else -> "Glasses: connected ${names.ifEmpty { "" }} ✓"
                }
            }
        }
    }

    private fun missingPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    // ---------- UI (plain views, no layout XML) ----------

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(Color.parseColor("#0B1620"))
        }

        col.addView(text("J.A.R.V.I.S.", 30f, "#4FD8FF", bold = true).apply { gravity = Gravity.CENTER })
        col.addView(text("Grok in your Meta glasses", 14f, "#8FB3C4").apply { gravity = Gravity.CENTER })
        statusView = text("Off", 22f, "#B8F3FF", bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6))
        }
        col.addView(statusView)

        col.addView(row(
            button("Start Jarvis") { startJarvis() },
            button("Stop") { JarvisService.send(this, JarvisService.ACTION_QUIT) },
        ))
        col.addView(row(
            button("Talk now") { startJarvis(JarvisService.ACTION_TALK) },
            button("Hush") { JarvisService.send(this, JarvisService.ACTION_HUSH) },
        ))

        col.addView(header("Setup"))
        col.addView(button("1. Grant phone permissions") {
            val missing = missingPermissions()
            if (missing.isEmpty()) toast("All set") else phonePermissions.launch(missing.toTypedArray())
        })
        col.addView(button("2. Link glasses (opens Meta AI)") {
            if ((application as JarvisApp).initWearables()) Wearables.startRegistration(this)
            else toast("Grant permissions first")
        })
        col.addView(button("3. Allow glasses camera") {
            if (JarvisApp.wearablesReady) glassesCameraPermission.launch(Permission.CAMERA)
            else toast("Grant permissions first")
        })
        glassesView = text("", 14f, "#8FB3C4").apply { setPadding(0, dp(6), 0, 0) }
        col.addView(glassesView)

        col.addView(header("Keys"))
        val xai = field("xAI API key (console.x.ai)", settings.xaiKey, secret = true)
        val pico = field("Picovoice AccessKey for \"Jarvis\" wake word (console.picovoice.ai)", settings.picovoiceKey, secret = true)
        col.addView(xai.first); col.addView(xai.second)
        col.addView(pico.first); col.addView(pico.second)

        col.addView(header("Personality"))
        val model = field("Grok model", settings.model)
        val voice = field("Grok voice: leo, rex, sal, ara, eve", settings.voice)
        val honorific = field("Jarvis calls you", settings.honorific)
        listOf(model, voice, honorific).forEach { col.addView(it.first); col.addView(it.second) }

        val grokVoice = switch("Speak with Grok's voice (off = phone's British voice)", settings.grokVoice)
        val followUps = switch("Keep listening for follow-up questions", settings.followUps)
        val camera = switch("Send what the glasses see", settings.useCamera)
        listOf(grokVoice, followUps, camera).forEach(col::addView)

        col.addView(button("Save settings") {
            settings.xaiKey = xai.second.text.toString()
            settings.picovoiceKey = pico.second.text.toString()
            settings.model = model.second.text.toString()
            settings.voice = voice.second.text.toString()
            settings.honorific = honorific.second.text.toString()
            settings.grokVoice = grokVoice.isChecked
            settings.followUps = followUps.isChecked
            settings.useCamera = camera.isChecked
            if (JarvisService.status.value != JarvisService.Status.OFF) {
                JarvisService.send(this, JarvisService.ACTION_RELOAD)
            }
            toast("Saved")
        })

        col.addView(header("Conversation"))
        transcriptView = text("", 15f, "#E6F7FF")
        col.addView(transcriptView)

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1620"))
            addView(col)
        }
    }

    private fun startJarvis(action: String? = null) {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            phonePermissions.launch(missing.toTypedArray())
            return
        }
        if (settings.xaiKey.isBlank()) toast("Add your xAI API key first")
        JarvisService.send(this, action)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun header(s: String) = text(s.uppercase(), 13f, "#4FD8FF", bold = true).apply {
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }

    private fun field(label: String, value: String, secret: Boolean = false): Pair<TextView, EditText> {
        val l = text(label, 13f, "#8FB3C4").apply { setPadding(0, dp(8), 0, 0) }
        val e = EditText(this).apply {
            setText(value)
            setTextColor(Color.WHITE)
            setSingleLine()
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
        }
        return l to e
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switch(label: String, checked: Boolean) = Switch(this).apply {
        text = label
        isChecked = checked
        setTextColor(Color.parseColor("#E6F7FF"))
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
