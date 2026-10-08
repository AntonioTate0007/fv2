package com.thumbshade.app.overlay

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.thumbshade.app.access.AssistService
import com.thumbshade.app.data.MultiWindowMode
import com.thumbshade.app.data.SettingsRepo
import com.thumbshade.app.rules.Effects

/**
 * Opens an app in split screen beside the current app, or in a pop-up window, where the phone
 * can do it. Split screen needs the accessibility service (Android only lets that, or the user,
 * put the screen into split view).
 */
object MultiWindow {
    private val main = Handler(Looper.getMainLooper())

    fun canSplit(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice != true

    fun canPopUp(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)

    /** Whether holding on an app can do anything on this phone right now. */
    fun available(context: Context): Boolean = when (SettingsRepo.current.multiWindowMode) {
        MultiWindowMode.SPLIT -> canSplit(context)
        MultiWindowMode.POP_UP -> canPopUp(context) || canSplit(context)
    }

    /** Why it can't work, for the settings screen; null when it can. */
    fun problem(context: Context): String? = when {
        SettingsRepo.current.multiWindowMode == MultiWindowMode.POP_UP && !canPopUp(context) ->
            "This phone doesn't offer pop-up windows to other apps; split screen is used instead."
        !canSplit(context) -> "This phone doesn't support split screen."
        AssistService.instance == null -> "Turn on the ThumbShade accessibility service (General tab) for split screen."
        else -> null
    }

    private fun launchIntent(context: Context, pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        )

    /** Opens [pkg] beside the current app (or in a pop-up). Falls back to opening it normally. */
    fun open(context: Context, pkg: String) {
        val intent = launchIntent(context, pkg) ?: return
        val mode = SettingsRepo.current.multiWindowMode
        if (mode == MultiWindowMode.POP_UP && canPopUp(context)) {
            val d = context.resources.displayMetrics
            val w = (d.widthPixels * 0.8f).toInt()
            val h = (d.heightPixels * 0.5f).toInt()
            val left = (d.widthPixels - w) / 2
            val top = (d.heightPixels * 0.15f).toInt()
            val opts = ActivityOptions.makeBasic().setLaunchBounds(Rect(left, top, left + w, top + h))
            runCatching { context.startActivity(intent, opts.toBundle()) }.onFailure { context.startActivity(intent) }
            return
        }
        val current = AssistService.state.value.foregroundPkg
        val assist = AssistService.instance
        if (assist == null) {
            Effects.toast(context, "Turn on the ThumbShade accessibility service for split screen")
            runCatching { context.startActivity(intent) }
            return
        }
        if (current == null || current == pkg) {
            // Nothing to split with (home screen, or the same app): just open it.
            runCatching { context.startActivity(intent) }
            return
        }
        // Put the current app into split screen, then open the chosen one in the other half.
        val ok = assist.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        main.postDelayed({
            runCatching { context.startActivity(intent) }.onFailure { Effects.toast(context, "Could not open it in split screen") }
        }, if (ok) 550L else 0L)
    }
}
