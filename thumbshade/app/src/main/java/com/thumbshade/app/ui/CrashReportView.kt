package com.thumbshade.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** The last crash report, built from plain views so it works even if Compose is what crashed. */
object CrashReportView {
    fun build(activity: Activity, report: String, onDismiss: () -> Unit): View {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 20, 23))
            setPadding(px(16), px(48), px(16), px(24))
        }
        root.addView(TextView(activity).apply {
            text = "ThumbShade crashed"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(activity).apply {
            text = "Tap Copy, then paste this to whoever is fixing the app. Dismiss opens the app normally."
            textSize = 14f
            setTextColor(Color.rgb(176, 182, 188))
            setPadding(0, px(6), 0, px(12))
        })
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit) = Button(activity).apply {
            text = label
            setOnClickListener { action() }
        }
        buttons.addView(button("Copy") {
            activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("ThumbShade crash", report))
            Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(button("Share") {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
            runCatching { activity.startActivity(Intent.createChooser(send, "Share crash report")) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(button("Dismiss") { onDismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
        val scroll = ScrollView(activity)
        scroll.addView(TextView(activity).apply {
            text = report
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(255, 138, 128))
            setTextIsSelectable(true)
            setPadding(0, px(12), 0, 0)
        })
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }
}
